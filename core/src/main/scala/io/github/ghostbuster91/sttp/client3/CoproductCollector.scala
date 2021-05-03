package io.github.ghostbuster91.sttp.client3

import cats.syntax.all._
import io.github.ghostbuster91.sttp.client3.ImportRegistry._
import io.github.ghostbuster91.sttp.client3.json.JsonTypeProvider
import io.github.ghostbuster91.sttp.client3.model._
import io.github.ghostbuster91.sttp.client3.openapi._
import scala.annotation.tailrec
import scala.meta._

class CoproductCollector(
    model: Model,
    enums: List[Enum],
    jsonTypeProvider: JsonTypeProvider
) {
  def collect(
      schemas: Map[SchemaRef, SafeSchema]
  ): IM[List[Coproduct]] =
    schemas
      .collect { case (k, c: SafeComposedSchema) =>
        for {
          oneOf <- collectOneOf(k, c)
          allOf <- collectAllOf(c)
        } yield (oneOf ++ allOf).toList
      }
      .toList
      .sequence
      .map(_.flatten.groupBy(_.name).values.map(_.head).toList)

  private def collectOneOf(
      key: SchemaRef,
      schema: SafeComposedSchema
  ): IM[Option[Coproduct]] =
    schema.oneOf.headOption
      .traverse(childRef => oneOfCoproduct(key, schema, childRef))

  private def oneOfCoproduct(
      key: SchemaRef,
      schema: SafeComposedSchema,
      childRef: SafeRefSchema
  ) =
    schema.discriminator match {
      case Some(dsc) =>
        val child =
          model
            .schemaFor(childRef.ref)
            .asInstanceOf[SafeObjectSchema]
        val discriminatorSchema = child.properties(dsc.propertyName)

        model
          .schemaToParameter(
            discriminatorSchema,
            child.requiredFields.contains(dsc.propertyName)
          )
          .map { paramRef =>
            Coproduct(
              model.classNameFor(key),
              List(paramRef.withName(dsc.propertyName)),
              Some(coproductDiscriminator(dsc, discriminatorSchema)),
              None
            )
          }
      case None =>
        Coproduct(
          model.classNameFor(key),
          List.empty,
          None,
          None
        ).pure[IM]
    }

  private def collectAllOf(schema: SafeComposedSchema) =
    schema.allOf.collect { case parent: SafeRefSchema =>
      allOfCoproduct(parent)
    }.sequence

  @tailrec
  private def extractAdditionalProperties(
      schema: SafeSchema
  ): IM[Option[ParameterRef]] =
    schema match {
      case s: SafeRefSchema =>
        extractAdditionalProperties(model.schemaFor(s.ref))
      case s: SafeMapSchema => handleAdditionalProps(s.additionalProperties)
      case _                => Option.empty[ParameterRef].pure[IM]
    }

  private def handleAdditionalProps(
      schema: Either[Boolean, SafeSchema]
  ): IM[Option[ParameterRef]] =
    schema match {
      case Right(schema) =>
        model.schemaToType(schema).map { tpe =>
          Some(
            ParameterRef(
              t"Map[String, $tpe]",
              ParameterName("_additionalProperties"),
              None
            )
          )
        }
      case Left(true) =>
        jsonTypeProvider.AnyType.map(anyType =>
          Some(
            ParameterRef(
              t"Map[String, $anyType]",
              ParameterName("_additionalProperties"),
              None
            )
          )
        )
      case Left(false) => Option.empty[ParameterRef].pure[IM]
    }

  @tailrec
  private def extractProperties(schema: SafeSchema): IM[List[ParameterRef]] =
    schema match {
      case s: SafeRefSchema        => extractProperties(model.schemaFor(s.ref))
      case s: SchemaWithProperties => extractObjectProperties(s)
    }

  private def extractObjectProperties(
      schema: SchemaWithProperties
  ): IM[List[ParameterRef]] =
    schema.properties.toList.traverse { case (k, v) =>
      model.schemaToParameter(k, v, schema.requiredFields.contains(k))
    }

  private def allOfCoproduct(
      parent: SafeRefSchema
  ): IM[Coproduct] =
    for {
      props <- extractProperties(parent)
      addProps <- extractAdditionalProperties(parent)
    } yield {
      val parentSchema = model
        .schemaFor(parent.ref)
        .asInstanceOf[SchemaWithProperties]
      Coproduct(
        model.classNameFor(parent.ref),
        props,
        parentSchema.discriminator
          .map { dsc =>
            val discriminatorSchema = parentSchema.properties(dsc.propertyName)
            coproductDiscriminator(dsc, discriminatorSchema)
          },
        addProps
      )
    }

  private def coproductDiscriminator(
      dsc: SafeDiscriminator,
      discriminatorSchema: SafeSchema
  ): Discriminator[_] =
    discriminatorSchema match {
      case _: SafeStringSchema =>
        Discriminator.StringDsc(
          dsc.propertyName,
          dsc.mapping
            .mapValues(ref => model.classNameFor(ref))
        )
      case _: SafeIntegerSchema =>
        Discriminator.IntDsc(
          dsc.propertyName,
          dsc.mapping.map { case (k, v) =>
            Integer.parseInt(k) -> model.classNameFor(v)
          }
        )
      case sr: SafeRefSchema =>
        val enumClassName = model.classNameFor(sr.ref)
        val enum = enums.find(e => e.name.v == enumClassName.v).get
        val enumMap: Map[EnumValue, ClassName] = enum match {
          case _: Enum.IntEnum =>
            dsc.mapping.map { case (k, v) =>
              EnumValue.IntEv(Integer.parseInt(k)) -> model.classNameFor(v)
            }
          case _: Enum.StringEnum =>
            dsc.mapping.map { case (k, v) =>
              EnumValue.StringEv(k) -> model.classNameFor(v)
            }
        }
        Discriminator.EnumDsc(
          dsc.propertyName,
          enum,
          enumMap
        )
    }

}
