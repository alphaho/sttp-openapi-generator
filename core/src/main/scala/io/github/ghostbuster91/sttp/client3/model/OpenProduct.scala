package io.github.ghostbuster91.sttp.client3.model

import scala.meta._

case class OpenProduct(
    name: ClassName,
    properties: Map[PropertyName, Type]
)

case class ClassName(v: String) {
  def asPrefix(postfix: String) =
    Pat.Var(Term.Name(s"${uncapitalized(v)}$postfix"))
  def term = Term.Name(v)
  def toVar: Term.Name = Term.Name(uncapitalized(v))
  def toFqnType(coproduct: Coproduct): Type =
    t"${coproduct.name.term}.$typeName"
  def typeName: Type.Name = Type.Name(v)
  def asParam: Pat = p"${Pat.Var(toVar)}: $typeName"
}

case class TypeRef(tpe: Type, paramName: String, defaultValue: Option[Term]) {
  def asParam: Term.Param = defaultValue match {
    case Some(value) => param"${Term.Name(paramName)} : $tpe = $value"
    case None        => param"${Term.Name(paramName)} : $tpe"
  }
}

object TypeRef {
  def apply(v: String, defaultValue: Option[Term]): TypeRef =
    new TypeRef(Type.Name(v), uncapitalized(v), defaultValue)
}

case class PropertyName(v: String) {
  def patVar: Pat = Pat.Var(Term.Name(v))
  def term: Term.Name = Term.Name(v)
}