#!/bin/bash

# This script is called by FireSim's bitbuilder to create a xclbin

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --cl_dir    : Custom logic directory to build AWS F1 bitstream from"
    echo "   --frequency : Frequency in MHz of the desired FPGA host clock."
    echo "   --strategy  : A string to a precanned set of build directives.
                          See aws-fpga documentation for more info/"
    echo "   --enable_pr       : Enable Partial Reconfiguration (true/false)"
    echo "   --pr_module_name  : Name(s) of the PR module(s), comma-separated if multiple (required if --enable_pr is true)"
    echo "   --pr_partition_path : Hierarchical path(s) to the PR partition(s), comma-separated if multiple (optional; discovered at synthesis if omitted)"
    echo "   --pr_base_dcp     : Path to a base build's post_route.dcp (optional, if specified uses main_pr_rm.tcl instead of the parent flow)"
    echo "   --pr_region       : Nested RP region for the parent flow, e.g. CLOCKREGION_X1Y8:CLOCKREGION_X1Y8 (default: pr_nested_region in pr_utils.tcl)"
    echo "   --build_tag       : Pin the build tag so parent and child agree on checkpoint names (optional, defaults to a timestamp)"
    echo "   --help      : Display this message"
    exit "$1"
}

CL_DIR=""
FREQUENCY=""
STRATEGY=""
ENABLE_PR="false"
PR_MODULE_NAME=""
PR_PARTITION_PATH=""
PR_BASE_DCP=""
BUILD_TAG=""

# getopts does not support long options, and is inflexible
# ensure $1 arg is empty or else hdk_setup.sh will fail
while [ "$1" != "" ];
do
    case $1 in
        --help)
            usage 1 ;;
        --cl_dir )
            shift
            CL_DIR=$1 ;;
        --strategy )
            shift
            STRATEGY=$1 ;;
        --frequency )
            shift
            FREQUENCY=$1 ;;
        --enable_pr )
            shift
            ENABLE_PR=$1 ;;
        --pr_module_name )
            shift
            PR_MODULE_NAME=$1 ;;
        --pr_partition_path )
            shift
            PR_PARTITION_PATH=$1 ;;
        --pr_base_dcp )
            shift
            PR_BASE_DCP=$1 ;;
        --pr_region )
            shift
            PR_REGION=$1 ;;
        --build_tag )
            shift
            BUILD_TAG=$1 ;;
        * )
            echo "invalid option $1"
            usage 1 ;;
    esac
    shift
done

if [ -z "$CL_DIR" ] ; then
    echo "no cl directory specified"
    usage 1
fi

if [ -z "$FREQUENCY" ] ; then
    echo "No --frequency specified"
    usage 1
fi

if [ -z "$STRATEGY" ] ; then
    echo "No --strategy specified"
    usage 1
fi

# Check PR arguments only if PR is enabled
if [ "$ENABLE_PR" = "true" ] ; then
    if [ -z "$PR_MODULE_NAME" ] ; then
        echo "No --pr_module_name specified (required when --enable_pr is true)"
        usage 1
    fi

    # pr_partition_path is optional (synthesis discovers it); validate counts only when provided
    if [ -n "$PR_PARTITION_PATH" ] ; then
        module_count=$(echo "$PR_MODULE_NAME" | tr ',' '\n' | wc -l)
        path_count=$(echo "$PR_PARTITION_PATH" | tr ',' '\n' | wc -l)
        if [ "$module_count" -ne "$path_count" ] ; then
            echo "Error: pr_module_name and pr_partition_path must have the same number of items"
            echo "  Found $module_count module name(s) and $path_count partition path(s)"
            usage 1
        fi
    fi

    if [ -n "$PR_BASE_DCP" ] && [ ! -f "$PR_BASE_DCP" ] ; then
        echo "Error: --pr_base_dcp does not exist: $PR_BASE_DCP"
        usage 1
    fi
fi

AWS_FPGA_DIR=$CL_DIR/../../../..

cd $AWS_FPGA_DIR
echo $AWS_FPGA_DIR

source hdk_setup.sh

export CL_DIR=$CL_DIR

# run build
cd $CL_DIR/build/scripts
# ./aws_build_dcp_from_cl.sh  -strategy $STRATEGY -frequency $FREQUENCY -foreground
export CL_NAME=$(basename $CL_DIR)

# Only used by the PR flows below.
DEVICE_TYPE="xcvu47p-fsvh2892-2-e"
CLOCK_RECIPE_A="A1"
CLOCK_RECIPE_B="B0"
CLOCK_RECIPE_C="C0"
# Same HBM recipe aws_build_dcp_from_cl.py uses in pass 1.
CLOCK_RECIPE_HBM="H2"

# Same format as aws_build_dcp_from_cl.py's build tag.
if [ -z "$BUILD_TAG" ] ; then
    BUILD_TAG=$(date +%Y_%m_%d-%H%M%S)
fi

if [ "$ENABLE_PR" != "true" ] ; then

./aws_build_dcp_from_cl.py -c $CL_NAME --frequency $FREQUENCY --aws_clk_gen --clock_recipe_a $CLOCK_RECIPE_A  --clock_recipe_b $CLOCK_RECIPE_B --clock_recipe_c $CLOCK_RECIPE_C --mode small_shell

elif [ -z "$PR_BASE_DCP" ] ; then

# Base (nested DFX) pass 1: AWS flow on the full CL (static + separately synthesized RM)
# with the RP region reserved.
echo "PR: parent pass 1/2 -- separately synthesized CL static and RM"
export FIRESIM_PR_ENABLE=1
export FIRESIM_PR_NESTED_PARENT=1
export FIRESIM_PR_MODULE_NAME="$PR_MODULE_NAME"
export FIRESIM_PR_FREQUENCY="$FREQUENCY"
if [ -n "$PR_REGION" ]; then
    export FIRESIM_PR_NESTED_REGION="$PR_REGION"
    echo "PR: nested RP region (recipe pr_region): $PR_REGION"
fi

./aws_build_dcp_from_cl.py -c $CL_NAME --frequency $FREQUENCY --aws_clk_gen --clock_recipe_a $CLOCK_RECIPE_A  --clock_recipe_b $CLOCK_RECIPE_B --clock_recipe_c $CLOCK_RECIPE_C --mode small_shell --tag $BUILD_TAG

unset FIRESIM_PR_ENABLE FIRESIM_PR_NESTED_PARENT FIRESIM_PR_MODULE_NAME FIRESIM_PR_FREQUENCY

# Pass 2: pr_subdivide makes the RM an RP inside WRAPPER/CL.
export SHELL_MODE="small_shell"
echo "PR: parent pass 2/2 -- pr_subdivide"
vivado -mode batch -source $CL_DIR/build/scripts/main_pr_subdivide.tcl -log ${BUILD_TAG}.subdivide.vivado.log -tclargs --cl_dir "$CL_DIR" --build_tag "$BUILD_TAG" --cl_name "$CL_NAME" --pr_module_name "$PR_MODULE_NAME" --clock_recipes "$CLOCK_RECIPE_A,$CLOCK_RECIPE_B,$CLOCK_RECIPE_C,$CLOCK_RECIPE_HBM"

echo "PR: base checkpoint to reuse for RM swaps:"
echo "PR:   $CL_DIR/build/checkpoints/${CL_NAME}.${BUILD_TAG}.post_route.dcp"

# AWS export (recombined); the nested base stays the child base.
AWS_EXPORT_DCP="$CL_DIR/build/checkpoints/${CL_NAME}.${BUILD_TAG}.aws_post_route.dcp"
vivado -mode batch -source "$CL_DIR/build/scripts/main_pr_export.tcl" -log "${BUILD_TAG}.export.vivado.log" -tclargs --base_dcp "$CL_DIR/build/checkpoints/${CL_NAME}.${BUILD_TAG}.post_route.dcp" --output_dcp "$AWS_EXPORT_DCP"
echo "PR: packaging recombined outer-CL checkpoint for AWS bitgen"
export BUILD_TAG="$BUILD_TAG"
python3 - "$CL_NAME" "$BUILD_TAG" "$CLOCK_RECIPE_A" "$CLOCK_RECIPE_B" "$CLOCK_RECIPE_C" "$CLOCK_RECIPE_HBM" "$AWS_EXPORT_DCP" <<'PY'
import sys
sys.path.insert(0, '.')
import aws_build_dcp_from_cl as awsbuild
awsbuild.generate_dcp_tarball(*sys.argv[1:7], dcp_path=sys.argv[7])
PY
echo "PR: outer-CL tarball: $CL_DIR/build/checkpoints/${BUILD_TAG}.Developer_CL.tar"

else

# Child (RM swap): aws_build_dcp_from_cl.py has no PR support, so drive vivado directly.
echo "PR: child (RM swap) build"
echo "PR:   module   : $PR_MODULE_NAME"
echo "PR:   base DCP : $PR_BASE_DCP"

RM_DCP="$CL_DIR/build/checkpoints/${PR_MODULE_NAME}.${BUILD_TAG}.rm_post_synth.dcp"

# 1. Synthesize the new RM out of context.
vivado -mode batch -source $CL_DIR/build/scripts/synth_cl_firesim_rm.tcl -log ${BUILD_TAG}.rm_synth.vivado.log -tclargs --cl_dir "$CL_DIR" --build_tag "$BUILD_TAG" --pr_module_name "$PR_MODULE_NAME" --frequency "$FREQUENCY" --device_type "$DEVICE_TYPE"

# 2. Swap it into the locked base and write the AWS export.
PR_PATH_ARG=""
if [ -n "$PR_PARTITION_PATH" ] ; then
    PR_PATH_ARG="--pr_partition_path $PR_PARTITION_PATH"
fi

# RapidWright routes the RM (scripts/rw); per-base inputs first.
RW_BASE_DIR="$CL_DIR/build/checkpoints/rw_base"
RW_ARG="--rw_base_dir $RW_BASE_DIR"
vivado -mode batch -source $CL_DIR/build/scripts/rw/main_pr_rwfst_base.tcl -log ${BUILD_TAG}.rw_base.vivado.log -tclargs --base_dcp "$PR_BASE_DCP" --out_dir "$RW_BASE_DIR" \
    || { echo "PR: RapidWright base prep failed; using the normal pr flow"; RW_ARG=""; }

vivado -mode batch -source $CL_DIR/build/scripts/main_pr_rm.tcl -log ${BUILD_TAG}.rm_swap.vivado.log -tclargs --cl_dir "$CL_DIR" --build_tag "$BUILD_TAG" --cl_name "$CL_NAME" --base_dcp "$PR_BASE_DCP" --rm_dcp "$RM_DCP" $PR_PATH_ARG $RW_ARG

# 3. Package with AWS's generate_dcp_tarball (it writes the DCP sha256).
export SHELL_MODE="small_shell"
export BUILD_TAG="$BUILD_TAG"
AWS_EXPORT_DCP="$CL_DIR/build/checkpoints/${CL_NAME}.${BUILD_TAG}.aws_post_route.dcp"
python3 - "$CL_NAME" "$BUILD_TAG" "$CLOCK_RECIPE_A" "$CLOCK_RECIPE_B" "$CLOCK_RECIPE_C" "$CLOCK_RECIPE_HBM" "$AWS_EXPORT_DCP" <<'PY'
import sys
sys.path.insert(0, '.')
import aws_build_dcp_from_cl as awsbuild
awsbuild.generate_dcp_tarball(*sys.argv[1:7], dcp_path=sys.argv[7])
PY

echo "PR: RM swap complete. Tarball:"
echo "PR:   $CL_DIR/build/checkpoints/${BUILD_TAG}.Developer_CL.tar"

fi
