package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.{Dtab, Name, NameTree, Namer, Path, Stack}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Activity

object DefaultInterpreterConfig {
  val kind = "default"
}

class DefaultInterpreterConfig extends InterpreterConfig {
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val Router.Namers(namers) = params[Router.Namers]
    ConfiguredNamersInterpreter(namers)
  }
}

class DefaultInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[DefaultInterpreterConfig]
  override val configId = DefaultInterpreterConfig.kind
}

object DefaultInterpreterInitializer extends DefaultInterpreterInitializer

/**
 * Namers are provided in preference-order so that first-match wins.
 */
case class ConfiguredNamersInterpreter(namers: Seq[(Path, Namer)])
  extends NameInterpreter {

  override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    Namer.bind(lookup(dtab), NameTree.Leaf(path))

  def lookup(path: Path): Activity[NameTree[Name]] =
    lookup(namers, path)

  /** Try to refine the name through the dtab, or bind it through a configured namer. */
  private[this] def lookup(dtab: Dtab)(path: Path): Activity[NameTree[Name]] =
    dtab.lookup(path) match {
      case NameTree.Neg => lookup(namers, path)
      case t => Activity.value(t)
    }

  private[this] def lookup(remaining: Seq[(Path, Namer)], path: Path): Activity[NameTree[Name]] =
    remaining match {
      // For now, punt to the default interpreter. Later, we
      // should consider removing the finagle Namer.global, which
      // provides /$/ names.
      case Nil => Namer.global.lookup(path)

      // try to lookup the path with the matching namer, or
      // fallback to the rest of the namers.
      case Seq((prefix, namer), remaining@_*) if path.startsWith(prefix) =>
        namer.lookup(path.drop(prefix.size)).flatMap {
          case NameTree.Neg => lookup(remaining, path)
          case t => Activity.value(t)
        }

      // Not a match, keep looking through namers.
      case Seq(_, remaining@_*) => lookup(remaining, path)
    }
}
