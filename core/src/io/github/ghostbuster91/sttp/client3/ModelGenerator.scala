package io.github.ghostbuster91.sttp.client3

import scala.meta._

class ModelGenerator(
    schemas: Map[String, SafeSchema],
    classNames: Map[SchemaRef, String]
) {
  def generate: Map[SchemaRef, Defn.Class] = {
    val model = schemas.map { case (key, schema: SafeObjectSchema) =>
      val schemaRef = SchemaRef.fromKey(key)
      schemaRef -> schemaToClassDef(
        classNames(schemaRef),
        schema
      )
    }
    model
  }

  def classNameFor(schemaRef: SchemaRef): String = classNames(schemaRef)

  private def schemaToClassDef(
      name: String,
      schema: SafeObjectSchema
  ) =
    Defn.Class(
      List(Mod.Case()),
      Type.Name(name),
      Nil,
      Ctor.Primary(
        Nil,
        Name(""),
        List(
          schema.properties.map { case (k, v) =>
            processParams(
              name,
              k,
              v,
              schema.requiredFields.contains(k)
            )
          }.toList
        )
      ),
      Template(Nil, Nil, Self(Name(""), None), Nil)
    )

  private def processParams(
      className: String,
      name: String,
      schema: SafeSchema,
      isRequired: Boolean
  ): Term.Param = {
    val declType = schemaToType(className, name, schema, isRequired)
    paramDeclFromType(name, declType)
  }

  def schemaToType(
      className: String,
      propertyName: String,
      schema: SafeSchema,
      isRequired: Boolean
  ): Type = {
    val declType = schemaToType(className, propertyName, schema)
    ModelGenerator.optionApplication(declType, isRequired)
  }

  private def schemaToType(
      className: String,
      propertyName: String,
      schema: SafeSchema
  ): Type =
    schema match {
      case ss: SafeStringSchema =>
        if (ss.isEnum) {
          Type.Name(
            s"${className.capitalize}${propertyName.capitalize}"
          )
        } else {
          Type.Name("String")
        }
      case si: SafeIntegerSchema =>
        if (si.isEnum) {
          Type.Name(
            s"${className.capitalize}${propertyName.capitalize}"
          )
        } else {
          Type.Name("Int")
        }
      case s: SafeArraySchema =>
        t"List[${schemaToType(className, propertyName, s.items)}]"
      case ref: SafeRefSchema => Type.Name(classNames(ref.ref))
    }

  private def paramDeclFromType(paramName: String, declType: Type) =
    Term.Param(
      Nil,
      Term.Name(paramName),
      Some(declType),
      None
    )
}

object ModelGenerator {
  def optionApplication(declType: Type, isRequired: Boolean): Type =
    if (isRequired) {
      declType
    } else {
      t"Option[$declType]"
    }
}