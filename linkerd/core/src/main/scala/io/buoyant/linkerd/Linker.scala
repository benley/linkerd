package io.buoyant.linkerd

import com.twitter.finagle.{param, Path, Namer, Stack}
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.{DefaultInterpreter, NameInterpreter}
import com.twitter.finagle.tracing.{NullTracer, DefaultTracer, BroadcastTracer, Tracer}
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.config._

/**
 * Represents the total configuration of a Linkerd process.
 */
trait Linker {
  def routers: Seq[Router]
  def namers: Seq[(Path, Namer)]
  def admin: Admin
  def tracer: Tracer
  def configured[T: Stack.Param](t: T): Linker
}

object Linker {

  private def ensureUniqueKinds(inits: Seq[ConfigInitializer]): Unit =
    inits.groupBy(_.configId).foreach {
      case (_, Seq(a, b, _*)) => throw ConflictingSubtypes(a.namedType, b.namedType)
      case _ =>
    }

  private[linkerd] case class Initializers(
    protocol: Seq[ProtocolInitializer] = Nil,
    namer: Seq[NamerInitializer] = Nil,
    interpreter: Seq[InterpreterInitializer] = Nil,
    tlsClient: Seq[TlsClientInitializer] = Nil,
    tracer: Seq[TracerInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(protocol, namer, interpreter, tlsClient, tracer)

    def parse(config: String): LinkerConfig =
      Linker.parse(config, this)

    def load(config: String): Linker =
      Linker.load(config, this)
  }

  private[linkerd] lazy val LoadedInitializers = Initializers(
    LoadService[ProtocolInitializer],
    LoadService[NamerInitializer],
    LoadService[InterpreterInitializer] :+ DefaultInterpreterInitializer,
    LoadService[TlsClientInitializer],
    LoadService[TracerInitializer]
  )

  private[linkerd] def parse(
    config: String,
    inits: Initializers = LoadedInitializers
  ): LinkerConfig = {
    val mapper = Parser.objectMapper(config)
    for (kinds <- inits.iter) {
      ensureUniqueKinds(kinds)
      for (k <- kinds) k.registerSubtypes(mapper)
    }
    mapper.readValue[LinkerConfig](config)
  }

  private[linkerd] def load(config: String, inits: Initializers): Linker =
    parse(config, inits).mk()

  def load(config: String): Linker =
    load(config, LoadedInitializers)

  case class LinkerConfig(
    namers: Option[Seq[NamerConfig]],
    routers: Seq[RouterConfig],
    tracers: Option[Seq[TracerConfig]],
    admin: Option[Admin]
  ) {
    def mk(): Linker = {
      // At least one router must be specified
      if (routers.isEmpty) throw NoRoutersSpecified

      val tracer: Tracer = tracers.map(_.map(_.newTracer())) match {
        case Some(Nil) => NullTracer
        case Some(Seq(tracer)) => tracer
        case Some(tracers) => BroadcastTracer(tracers)
        case None => DefaultTracer
      }

      val namerParams = Stack.Params.empty + param.Tracer(tracer)
      val namersByPrefix = namers.getOrElse(Nil).reverse.map { namer =>
        namer.prefix -> namer.newNamer(namerParams)
      }

      // Router labels must not conflict
      for ((label, rts) <- routers.groupBy(_.label))
        if (rts.size > 1) throw ConflictingLabels(label)

      val routerParams = namerParams + Router.Namers(namersByPrefix)
      val routerImpls = routers.map { router =>
        val interpreter = router.interpreter.newInterpreter(routerParams)
        router.router(routerParams + DstBindingFactory.Namer(interpreter))
      }

      // Server sockets must not conflict
      for (srvs <- routerImpls.flatMap(_.servers).groupBy(_.addr).values)
        srvs match {
          case Seq(srv0, srv1, _*) => throw ConflictingPorts(srv0.addr, srv1.addr)
          case _ =>
        }

      new Impl(routerImpls, namersByPrefix, tracer, admin.getOrElse(Admin()))
    }
  }

  /**
   * Private concrete implementation, to help protect compatibility if
   * the Linker api is extended.
   */
  private case class Impl(
    routers: Seq[Router],
    namers: Seq[(Path, Namer)],
    tracer: Tracer,
    admin: Admin
  ) extends Linker {
    override def configured[T: Stack.Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
