package metaconfig.internal

import metaconfig._
import metaconfig.generic.Setting
import metaconfig.generic.Settings
import metaconfig.Configured.ok
import metaconfig.annotation.Inline
import scala.collection.immutable.Nil
import scala.collection.mutable

object CliParser {

  def parseArgs[T](
      args: List[String]
  )(implicit settings: Settings[T]): Configured[Conf] = {
    val toInline = inlinedFields(settings)
    def loop(
        curr: Conf.Obj,
        xs: List[String],
        s: State
    ): Configured[Conf.Obj] = {
      def add(key: String, value: Conf): Conf.Obj = {
        val values = curr.values.filterNot {
          case (k, _) => k == key
        }
        Conf.Obj((key, value) :: values)
      }

      (xs, s) match {
        case (Nil, NoFlag) => ok(curr)
        case (Nil, Flag(flag, setting)) =>
          if (setting.isBoolean) ok(add(flag, Conf.fromBoolean(true)))
          else {
            ConfError
              .message(
                s"the argument '--$flag' requires a value but none was supplied"
              )
              .notOk
          }
        case (head :: tail, NoFlag) =>
          val equal = head.indexOf('=')
          if (equal >= 0) { // split "--key=value" into ["--key", "value"]
            val key = head.substring(0, equal)
            val value = head.substring(equal + 1)
            loop(curr, key :: value :: tail, NoFlag)
          } else if (head.startsWith("-")) {
            val camel = Case.kebabToCamel(dash.replaceFirstIn(head, ""))
            camel.split("\\.").toList match {
              case Nil =>
                ConfError.message(s"Flag '$head' must not be empty").notOk
              case flag :: flags =>
                val (key, keys) = toInline.get(flag) match {
                  case Some(setting :: settings) =>
                    setting.name -> (flag :: flags)
                  case _ => flag -> flags
                }
                settings.get(key, keys) match {
                  case None =>
                    val closestCandidate =
                      Levenshtein.closestCandidate(camel, settings.names)
                    val didYouMean = closestCandidate match {
                      case None =>
                        ""
                      case Some(candidate) =>
                        val kebab = Case.camelToKebab(candidate)
                        s"\n\tDid you mean '--$kebab'?"
                    }
                    ConfError
                      .message(
                        s"found argument '--$flag' which wasn't expected, or isn't valid in this context.$didYouMean"
                      )
                      .notOk
                  case Some(setting) =>
                    val prefix = toInline.get(flag).fold("")(_.name + ".")
                    val toAdd = prefix + camel
                    if (setting.isBoolean) {
                      val newCurr = add(toAdd, Conf.fromBoolean(true))
                      loop(newCurr, tail, NoFlag)
                    } else {
                      loop(curr, tail, Flag(toAdd, setting))
                    }
                }
            }
          } else {
            val key = "remainingArgs"
            val positionalArgs =
              addRepeated(curr, key, Conf.fromString(head))
            loop(add(key, positionalArgs), tail, NoFlag)
          }
        case (head :: tail, Flag(flag, setting)) =>
          val value = Conf.fromString(head)
          val newCurr =
            if (setting.isRepeated) {
              addRepeated(curr, flag, value)
            } else {
              value
            }
          loop(add(flag, newCurr), tail, NoFlag)
      }
    }
    loop(Conf.Obj(), args, NoFlag).map(_.normalize)
  }

  private def inlinedFields[T](
      settings: Settings[T]
  ): Map[String, List[Setting]] = {
    val result = mutable.Map.empty[String, mutable.ListBuffer[Setting]]
    settings.settings.iterator.foreach { setting =>
      if (setting.annotations.exists(_.isInstanceOf[Inline])) {
        for {
          underlying <- setting.underlying.toList
          name <- underlying.names
        } {
          val buf = result.getOrElseUpdate(name, mutable.ListBuffer.empty)
          buf += setting
        }
      }
    }
    result.mapValues(_.toList).toMap
  }

  private def addRepeated(conf: Conf.Obj, key: String, value: Conf): Conf = {
    conf.map.get(key) match {
      case Some(Conf.Lst(values)) => Conf.Lst(values :+ value)
      case _ => Conf.Lst(value :: Nil)
    }
  }

  private sealed trait State
  private case class Flag(flag: String, setting: Setting) extends State
  private case object NoFlag extends State
  private val dash = "--?".r

}
