package autowire

import scala.concurrent.Future
import scala.reflect.macros.Context
import language.experimental.macros
import scala.annotation.Annotation
import scala.collection.mutable
import acyclic.file

import Core._

object Macros {

  sealed trait Check[T] {
    def map[V](f: T => V): Check[V]
    def flatMap[V](f: T => Check[V]): Check[V]
    def withFilter(f: T => Boolean): Check[T]
  }
  case class Luz[T](s: String) extends Check[T]{
    def map[V](f: T => V) = Luz[V](s)
    def flatMap[V](f: T => Check[V]) = Luz[V](s)
    def withFilter(f: T => Boolean) = Luz[T](s)
  }

  case class Win[T](t: T, s: String) extends Check[T]{
    def map[V](f: T => V) = Win(f(t), s)
    def flatMap[V](f: T => Check[V]) = f(t)
    def withFilter(f: T => Boolean) = if (f(t)) this else Luz(s)
  }

  def futurize(c: Context)(t: c.Tree, member: c.Symbol) = {
    import c.universe._
    if (member.asMethod.returnType <:< c.typeOf[Future[_]]) {
      t
    } else {
      q"scala.concurrent.Future.successful($t)"
    }
  }
  def clientMacro[Result]
                 (c: Context)
                 ()
                 (implicit r: c.WeakTypeTag[Result])
                 : c.Expr[Future[Result]] = {
    import c.universe._
    // If the tree is one of those default-argument containing blocks or
    // functions, pry it apart such that the main logic can operate on the
    // inner tree, and leave instructions on how
    val res = for {
      q"$pkg.`package`.$callableName[$t]($contents)" <- Win(c.prefix.tree,
        "You can only .call() on the Proxy returned by autowire.Client.apply, not " + c.prefix.tree
      )
      if Seq("autowire.this", "autowire").contains(pkg.toString)
      if Seq("clientFutureCallable", "clientCallable").contains(callableName.toString)
      (unwrapTree: Tree, methodName: TermName, args: Seq[Tree], prelude: Seq[Tree], deadNames: Seq[String]) = (contents: Tree) match{
        case x @ q"$thing.$call(..$args)" => (thing, call, args, Nil, Nil)
        case t @ q"..${statements: List[ValDef]}; $thing.$call(..$args)"
          if statements.forall(_.isInstanceOf[ValDef]) =>

          val (liveStmts, deadStmts) = statements.tail.partition {
            case ValDef(mod, _, _, Select(singleton, name))
              if name.toString.contains("$default") => false
            case _ => true
          }
          val ValDef(_, _, _, rhs) = statements.head

          (rhs, call, args, liveStmts, deadStmts.map(_.name))
        case x =>
          c.abort(x.pos, s"YY You can't call the .call() method on $x, only on autowired function calls")
      }

      q"autowire.this.`package`.unwrapClientProxy[$trt, $pt, $rb, $wb]($proxy)" <- Win(unwrapTree,
        s"XX You can't call the .call() method  on $contents, only on autowired function calls"
      )
      path = trt.tpe
                .widen
                .typeSymbol
                .fullName
                .toString
                .split('.')
                .toSeq
                .:+(methodName.toString)
      method = (trt.tpe: Type).decl(methodName).asMethod
      pickled = args
        .zip(method.paramLists.flatten)
        .filter{
          case (Ident(name: TermName), _) => !deadNames.contains(name)
          case (q"$thing.$name", _) if name.toString.contains("$default$") => false
          case _ => true
        }
        .map{case (t, param: Symbol) => q"${param.name.toString} -> $proxy.self.write($t)"}

    } yield {
      q"""{
        ..$prelude;
        $proxy.self.doCall(
          autowire.Core.Request(Seq(..$path), Map(..$pickled))
        ).map($proxy.self.read[${r}](_))
      }"""
    }

    res match{
      case Win(tree, s) => c.Expr[Future[Result]](tree)
      case Luz(s) => c.abort(c.enclosingPosition, s)
    }

  }


  def routeMacro[Trait, PickleType]
                (c: Context)
                (target: c.Expr[Trait])
                (implicit t: c.WeakTypeTag[Trait], pt: c.WeakTypeTag[PickleType])
                : c.Expr[Router[PickleType]] = {
//    println("-----------------------------------------------------")

    import c.universe._

    val tree = target.tree
    val apiClass = weakTypeOf[Trait]
    val routes: Seq[Tree] = for{
      member <- apiClass.decls.toSeq
      // not some rubbish defined on AnyRef
      if !weakTypeOf[AnyRef].members.exists(_.name == member.name)
      // Not a default value synthetic methods
      if !member.isSynthetic
    } yield {
      val path = apiClass.typeSymbol.fullName.toString.split('.').toSeq :+ member.name.toString
      val flatArgs =
        member.typeSignature
              .paramLists
              .flatten
              .zipWithIndex

      def hasDefault(arg: Symbol, i: Int) = {
        val defaultName = s"${member.name}$$default$$${i+1}"
        if (tree.symbol.asModule.typeSignature.members.exists(_.name.toString == defaultName))
          Some(defaultName)
        else
          None
      }
      val argName = c.freshName[TermName]("args")
      val args = flatArgs.map{ case (arg, i) =>
        hasDefault(arg, i) match{
          case Some(defaultName) => q"""
            $argName.get(${arg.name.toString})
                    .fold($target.${TermName(defaultName)})( x =>
                   try ${c.prefix}.read[${arg.typeSignature}](x)
                   catch autowire.Internal.invalidHandler
                 )
          """
          case None => q"""
            try ${c.prefix}.read[${arg.typeSignature}]($argName(${arg.name.toString}))
            catch autowire.Internal.invalidHandler
          """
        }
      }

      val requiredArgs = flatArgs.collect{
        case (arg, i) if hasDefault(arg, i).isEmpty => arg.name.toString
      }

      val frag = cq""" autowire.Core.Request(Seq(..$path), $argName) =>
        val keySet = $argName.keySet
        val missing = Array(..$requiredArgs).filterNot(keySet.contains)
        if (!missing.isEmpty)
          throw new autowire.Error.MissingParams(missing)
        ${futurize(c)(q"$target.$member(..$args)", member)}.map(${c.prefix}.write(_))
      """
      frag
    }

    val res = q"{case ..$routes}: autowire.Core.Router[$pt]"
//    println(res)
    c.Expr(res)
  }
}

