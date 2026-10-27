// See LICENSE for license details.

package midas.passes

import java.security.MessageDigest

import scala.collection.mutable

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.analyses.InstanceKeyGraph
import firrtl.transforms.DontTouchAnnotation
import firrtl.options.Dependency
import firrtl.stage.Forms

import org.json4s._
import org.json4s.native.JsonMethods._

import midas.stage.GoldenGateOutputFileAnnotation
import midas.targetutils.FirrtlReconfigurablePartitionAnnotation
import midas.targetutils.xdc._
import midas.InternalXDCAnnotation

/** A port of a reconfigurable partition, as lowered before any Golden Gate optimization. */
case class ReconfigurablePartitionPort(name: String, direction: String, width: BigInt)

/** The reference boundary of one reconfigurable partition, recorded after target lowering (before ConstantPropagation
  * and DeadCodeElimination). `id` is the partition's instance target at Golden Gate's input; it survives renames.
  */
case class ReconfigurablePartitionPortsAnnotation(
  target: InstanceTarget,
  id:     String,
  ports:  Seq[ReconfigurablePartitionPort],
  hash:   String,
) extends SingleTargetAnnotation[InstanceTarget] {
  def duplicate(n: InstanceTarget) = this.copy(target = n)
}

/** Every partition recorded at Golden Gate's input, so one that disappears (removed or inlined) is reported. */
case class ReconfigurablePartitionIdsAnnotation(ids: Seq[String]) extends NoTargetAnnotation

/** Per-instance structural hash manifest of the CHIRRTL that Golden Gate reads (port of firrtl_pass HashManifest.cpp,
  * same "chisel-hash-manifest/1" JSON). For every module:
  *   - iface: port names, directions and types;
  *   - body: iface + every statement with local names replaced by declaration order and source info dropped; an
  *     instance contributes the child's iface hash instead of its module name;
  *   - full: body + the full hashes of the children in instance order (Merkle).
  */
object ReconfigurablePartitionHashManifest {
  private case class ModInfo(
    kind:        String,
    iface:       Array[Byte],
    body:        Array[Byte],
    children:    Seq[(String, String)],
    nPorts:      Int,
    portBits:    BigInt,
    unknownBits: Int,
    nOps:        Int,
  )

  private class Sha {
    private val md = MessageDigest.getInstance("SHA-256")
    def raw(s: String): Unit = {
      val b = s.getBytes("UTF-8")
      md.update(BigInt(b.length).toByteArray.reverse.padTo(8, 0.toByte))
      md.update(b)
    }
    def bytes(b: Array[Byte]): Unit = md.update(b)
    def done: Array[Byte] = md.digest()
  }

  def hex(d: Array[Byte]): String = d.take(8).map("%02x".format(_)).mkString

  private def portBits(t: Type): Option[BigInt] =
    scala.util.Try(firrtl.bitWidth(t)).toOption

  /** Local names become p<i> (ports, in order) and v<n> (declarations, in pre-order). */
  private class Canon(ports: Seq[String], ifaceOf: String => String) {
    private val ids                             = mutable.HashMap[String, String]()
    ports.zipWithIndex.foreach { case (p, i) => ids(p) = s"p$i" }
    private var next                            = 0
    var nOps                                    = 0
    private def declare(n: String): String      = { val c = s"v$next"; next += 1; ids(n) = c; c }
    private def ref(n: String): String          = ids.getOrElse(n, n)
    private def expr(e: Expression): Expression = e match {
      case r: Reference => r.copy(name = ref(r.name))
      case other        => other.mapExpr(expr)
    }
    def stmt(s: Statement): Statement = {
      val out = s match {
        case b: Block         => b.mapStmt(stmt)
        case c: Conditionally => c.mapExpr(expr).mapStmt(stmt)
        case i: DefInstance   => i.copy(name = declare(i.name), module = ifaceOf(i.module))
        case m: CDefMemory    => m.copy(name = declare(m.name))
        case p: CDefMPort     => p.copy(name = declare(p.name), mem = ref(p.mem)).mapExpr(expr)
        case d: IsDeclaration => d.mapString(declare).mapExpr(expr)
        case other            => other.mapExpr(expr)
      }
      s match {
        case _: Block => out
        case _        => nOps += 1; out.mapInfo(_ => NoInfo)
      }
    }
  }

  /** Returns the manifest JSON and each module's full hash. */
  def apply(circuit: Circuit): (String, Map[String, String]) = {
    val mods = circuit.modules.map(m => m.name -> m).toMap

    val ifaceOf = circuit.modules.map { m =>
      val h = new Sha
      h.raw("iface/1")
      m.ports.foreach { p =>
        h.raw(p.name); h.raw(if (p.direction == Input) "in" else "out"); h.raw(p.tpe.serialize)
      }
      m.name -> h.done
    }.toMap

    val infos = circuit.modules.map { m =>
      val children = mutable.ArrayBuffer[(String, String)]()
      def collect(s: Statement): Unit = s match {
        case i: DefInstance => children += (i.name -> i.module)
        case other          => other.foreachStmt(collect)
      }
      val h        = new Sha
      h.raw("body/1")
      val canon    = new Canon(m.ports.map(_.name), n => ifaceOf.get(n).map(hex).getOrElse(n))
      m match {
        case mod: Module    =>
          h.raw("firrtl.module"); h.bytes(ifaceOf(m.name))
          collect(mod.body)
          h.raw(canon.stmt(mod.body).serialize)
        case ext: ExtModule =>
          h.raw("firrtl.extmodule"); h.bytes(ifaceOf(m.name))
          h.raw(ext.defname); ext.params.foreach(p => h.raw(p.serialize))
      }
      val widths   = m.ports.map(p => portBits(p.tpe))
      m.name -> ModInfo(
        kind        = if (m.isInstanceOf[ExtModule]) "firrtl.extmodule" else "firrtl.module",
        iface       = ifaceOf(m.name),
        body        = h.done,
        children    = children.toSeq,
        nPorts      = m.ports.size,
        portBits    = widths.flatten.sum,
        unknownBits = widths.count(_.isEmpty),
        nOps        = canon.nOps,
      )
    }.toMap

    val full = mutable.HashMap[String, Array[Byte]]()
    def fullOf(name: String): Array[Byte] = full.getOrElseUpdate(
      name, {
        val h = new Sha
        h.bytes(infos(name).body)
        infos(name).children.foreach { case (_, c) => if (infos.contains(c)) h.bytes(fullOf(c)) }
        h.done
      },
    )
    circuit.modules.foreach(m => fullOf(m.name))

    def modJson(n: String) = {
      val i = infos(n)
      JObject(
        "kind"                -> JString(i.kind),
        "iface"               -> JString(hex(i.iface)),
        "body"                -> JString(hex(i.body)),
        "full"                -> JString(hex(full(n))),
        "n_ports"             -> JInt(i.nPorts),
        "port_bits"           -> JInt(i.portBits),
        "ports_unknown_width" -> JInt(i.unknownBits),
        "n_ops"               -> JInt(i.nOps),
        "children"            -> JArray(i.children.map { case (a, b) => JArray(List(JString(a), JString(b))) }.toList),
      )
    }
    val instances                    = mutable.ArrayBuffer[JValue]()
    def walk(path: String, n: String): Unit = {
      instances += JObject(
        "path"   -> JString(path),
        "module" -> JString(n),
        "iface"  -> JString(hex(infos(n).iface)),
        "body"   -> JString(hex(infos(n).body)),
        "full"   -> JString(hex(full(n))),
      )
      infos(n).children.foreach { case (inst, c) => if (infos.contains(c)) walk(s"$path/$inst", c) }
    }
    if (mods.contains(circuit.main)) walk(circuit.main, circuit.main)

    val json = JObject(
      "format"    -> JString("chisel-hash-manifest/1"),
      "circuit"   -> JString(circuit.main),
      "top"       -> JString(circuit.main),
      "modules"   -> JObject(circuit.modules.map(m => m.name -> modJson(m.name)).toList),
      "instances" -> JArray(instances.toList),
    )
    (pretty(render(json)) + "\n", full.map { case (k, v) => k -> hex(v) }.toMap)
  }
}

/** The hash manifest of a CHIRRTL file without running Golden Gate, so a build can choose its partition first.
  * usage: ReconfigurablePartitionHashManifestMain <input.fir> <output.json>
  */
object ReconfigurablePartitionHashManifestMain {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "usage: ReconfigurablePartitionHashManifestMain <input.fir> <output.json>")
    val (json, _) = ReconfigurablePartitionHashManifest(Parser.parseFile(args(0), Parser.UseInfo))
    val out       = new java.io.PrintWriter(args(1))
    try out.write(json)
    finally out.close()
  }
}

/** Checks that every reconfigurable partition (see midas.targetutils.ReconfigurablePartition) reaches the emitted
  * Verilog with the boundary it had after target lowering, reports its final path, and tells Vivado to keep it.
  *   - error: partition removed or inlined, or ports added, removed or resized;
  *   - warning: direct input-to-output feedthroughs, inputs the parent ties to a constant;
  *   - output: FireSim-generated.reconfigurable-partitions.json, KEEP_HIERARCHY + DONT_TOUCH in .synthesis.xdc.
  */
object ReconfigurablePartitionBoundaries extends Transform with DependencyAPIMigration {
  override def prerequisites          = Forms.LowForm
  override def optionalPrerequisiteOf = Seq(Dependency(WriteXDCFile))
  override def invalidates(a: Transform) = false

  private def lowPorts(m: DefModule): Seq[ReconfigurablePartitionPort] = m.ports.map { p =>
    ReconfigurablePartitionPort(p.name, if (p.direction == Input) "in" else "out", firrtl.bitWidth(p.tpe))
  }

  /** At Golden Gate's input (CHIRRTL): the hash manifest, written only when the design marks a partition, and
    * dontTouch on every partition port (a partition marked from an annotation file has no Chisel-side dontTouch).
    */
  def manifest(state: CircuitState): (Seq[Annotation], Map[String, String]) = {
    val parts = state.annotations.collect { case a: FirrtlReconfigurablePartitionAnnotation => a }
    if (parts.isEmpty) (Nil, Map.empty)
    else {
      val (json, fullOf) = ReconfigurablePartitionHashManifest(state.circuit)
      val mods           = state.circuit.modules.map(m => m.name -> m).toMap
      val keep           = parts.flatMap { a =>
        mods.get(a.target.ofModule).toSeq.flatMap(_.ports).map { p =>
          DontTouchAnnotation(ReferenceTarget(state.circuit.main, a.target.ofModule, Nil, p.name, Nil))
        }
      }
      (GoldenGateOutputFileAnnotation(json, ".hash-manifest.json") +: keep, fullOf)
    }
  }

  /** After target lowering, before Golden Gate's optimizations: record each partition's reference port list. */
  def recordPorts(state: CircuitState, fullOf: Map[String, String]): CircuitState = {
    val mods   = state.circuit.modules.map(m => m.name -> m).toMap
    val annos  = state.annotations.collect { case a: FirrtlReconfigurablePartitionAnnotation => a }
    val record = annos.map { a =>
      val m = mods.getOrElse(
        a.target.ofModule,
        throw new FirrtlUserException(s"reconfigurable partition ${a.target.serialize}: no module ${a.target.ofModule}"),
      )
      ReconfigurablePartitionPortsAnnotation(a.target, a.target.serialize, lowPorts(m), fullOf.getOrElse(m.name, ""))
    }
    if (record.isEmpty) state
    else state.copy(annotations = state.annotations ++ record :+ ReconfigurablePartitionIdsAnnotation(record.map(_.id)))
  }

  /** Inputs of `m` that reach an output through wires and nodes only (no registers, memories or sub-instances). */
  private def feedthroughs(m: Module): Seq[(String, String)] = {
    val deps                                          = mutable.HashMap[String, Seq[String]]()
    def refs(e: Expression): Seq[String]              = e match {
      case Reference(n, _, _, _) => Seq(n)
      case _: SubField           => Nil // instance or memory port: not followed
      case other                 =>
        val acc = mutable.ArrayBuffer[String](); other.foreachExpr(x => acc ++= refs(x)); acc.toSeq
    }
    def visit(s: Statement): Unit                     = s match {
      case DefNode(_, n, e)                => deps(n) = refs(e)
      case Connect(_, Reference(n, _, _, _), e) => deps(n) = deps.getOrElse(n, Nil) ++ refs(e)
      case other                           => other.foreachStmt(visit)
    }
    visit(m.body)
    val regs                                          = mutable.HashSet[String]()
    def regsOf(s: Statement): Unit                    = s match {
      case r: DefRegister => regs += r.name
      case other          => other.foreachStmt(regsOf)
    }
    regsOf(m.body)
    val inputs                                        = m.ports.filter(_.direction == Input).map(_.name).toSet
    val memo                                          = mutable.HashMap[String, Set[String]]()
    def sources(n: String, seen: Set[String]): Set[String] =
      if (inputs(n)) Set(n)
      else if (regs(n) || seen(n)) Set.empty
      else memo.getOrElseUpdate(n, deps.getOrElse(n, Nil).flatMap(sources(_, seen + n)).toSet)
    for {
      o <- m.ports.filter(_.direction == Output).map(_.name)
      i <- sources(o, Set.empty).toSeq.sorted
    } yield i -> o
  }

  /** Input ports of instance `inst` in `parent` connected to a literal. */
  private def constantInputs(parent: Module, inst: String, inputs: Set[String]): Seq[String] = {
    val nodes                                         = mutable.HashMap[String, Expression]()
    val out                                           = mutable.ArrayBuffer[String]()
    def isConst(e: Expression): Boolean               = e match {
      case _: Literal               => true
      case Reference(n, _, _, _)    => nodes.get(n).exists(_.isInstanceOf[Literal])
      case _                        => false
    }
    def visit(s: Statement): Unit                     = s match {
      case DefNode(_, n, e)                                                         => nodes(n) = e
      case Connect(_, SubField(Reference(`inst`, _, _, _), p, _, _), e) if inputs(p) && isConst(e) => out += p
      case other                                                                    => other.foreachStmt(visit)
    }
    visit(parent.body)
    out.toSeq.sorted
  }

  def execute(state: CircuitState): CircuitState = {
    val recorded = state.annotations.collect { case a: ReconfigurablePartitionPortsAnnotation => a }
    val expected = state.annotations.collect { case ReconfigurablePartitionIdsAnnotation(ids) => ids }.flatten
    if (expected.isEmpty) return state

    val missing = expected.filterNot(id => recorded.exists(_.id == id))
    if (missing.nonEmpty)
      throw new FirrtlUserException(s"reconfigurable partitions removed or inlined: ${missing.mkString(", ")}")

    val mods                = state.circuit.modules.map(m => m.name -> m).toMap
    val iGraph              = InstanceKeyGraph(state.circuit)
    val (preLink, postLink) = state.annotations.collectFirst { case XDCPathToCircuitAnnotation(pre, post) =>
      (pre, post)
    }.getOrElse((None, None))
    val main                = state.circuit.main

    val results = recorded.map { a =>
      val parent = mods.get(a.target.module).collect { case m: Module => m }.getOrElse(
        throw new FirrtlUserException(s"reconfigurable partition ${a.id}: parent ${a.target.module} not found")
      )
      def instOf(s: Statement): Option[String] = s match {
        case i: DefInstance if i.name == a.target.instance => Some(i.module)
        case b: Block                                      => b.stmts.view.flatMap(instOf).headOption
        case c: Conditionally                              => instOf(c.conseq).orElse(instOf(c.alt))
        case _                                             => None
      }
      val child  = instOf(parent.body).flatMap(mods.get).getOrElse(
        throw new FirrtlUserException(s"reconfigurable partition ${a.id}: instance removed or inlined")
      )
      val now    = lowPorts(child)
      if (now != a.ports) {
        val (was, is) = (a.ports.toSet, now.toSet)
        throw new FirrtlUserException(
          s"reconfigurable partition ${a.id}: boundary changed during compilation; " +
            s"lost ${(was -- is).map(p => s"${p.name}[${p.width}]").mkString(" ")}, " +
            s"gained ${(is -- was).map(p => s"${p.name}[${p.width}]").mkString(" ")}"
        )
      }
      val ft     = child match { case m: Module => feedthroughs(m); case _ => Nil }
      val consts = constantInputs(parent, a.target.instance, now.filter(_.direction == "in").map(_.name).toSet)
      ft.foreach { case (i, o) => logger.warn(s"reconfigurable partition ${a.id}: feedthrough $i -> $o") }
      consts.foreach(p => logger.warn(s"reconfigurable partition ${a.id}: input $p tied to a constant by the parent"))

      val paths = iGraph.findInstancesInHierarchy(parent.name).map(_.tail.map(_.Instance.value) :+ a.target.instance)
      val rt    = ReferenceTarget(main, parent.name, Nil, a.target.instance, Nil)
      val xdc   = InternalXDCAnnotation(
        XDCFiles.Synthesis,
        "set_property KEEP_HIERARCHY TRUE [get_cells {}]\nset_property DONT_TOUCH TRUE [get_cells {}]",
        rt,
        rt,
      )
      val json  = JObject(
        "id"              -> JString(a.id),
        "module"          -> JString(child.name),
        "pre_link_paths"  -> JArray(paths.map(p => JString((preLink.toSeq ++ p).mkString("/"))).toList),
        "post_link_paths" -> JArray(paths.map(p => JString((postLink.toSeq ++ p).mkString("/"))).toList),
        "hash"            -> JString(a.hash),
        "port_bits"       -> JInt(now.map(_.width).sum),
        "ports"           -> JArray(now.map(p => JObject("name" -> JString(p.name), "direction" -> JString(p.direction),
          "width" -> JInt(p.width))).toList),
        "feedthroughs"    -> JArray(ft.map { case (i, o) => JArray(List(JString(i), JString(o))) }.toList),
        "constant_inputs" -> JArray(consts.map(JString(_)).toList),
      )
      (xdc, json)
    }

    val file    = GoldenGateOutputFileAnnotation(
      pretty(render(JObject("partitions" -> JArray(results.map(_._2).toList)))) + "\n",
      ".reconfigurable-partitions.json",
    )
    val cleaned = state.annotations.filterNot {
      case _: ReconfigurablePartitionPortsAnnotation  => true
      case _: ReconfigurablePartitionIdsAnnotation    => true
      case _: FirrtlReconfigurablePartitionAnnotation => true
      case _                                          => false
    }
    state.copy(annotations = cleaned ++ results.map(_._1) :+ file)
  }
}
