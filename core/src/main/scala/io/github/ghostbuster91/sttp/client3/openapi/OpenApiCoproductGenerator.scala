package io.github.ghostbuster91.sttp.client3.openapi

import io.swagger.v3.oas.models.media.ComposedSchema
import scala.collection.JavaConverters._

object OpenApiCoproductGenerator {
  def generate(openApi: SafeOpenApi): SafeOpenApi = {
    val coproducts = collectCoproducts(openApi)
    val childToParent = coproducts.values
      .flatMap(parent => parent.oneOf.map(child => child.ref -> parent))
      .toMap
    val errorsWithoutCommonParent =
      collectCandidates(openApi, childToParent, collectErrorResponses)
    val successesWithoutCommonParent =
      collectCandidates(openApi, childToParent, collectSuccessResponses)
    val newCoproducts = errorsWithoutCommonParent
      .map(kv =>
        createCoproduct(kv._1, kv._2, "GenericError")
      ) ++ successesWithoutCommonParent
      .map(kv => createCoproduct(kv._1, kv._2, "GenericSuccess"))

    registerNewSchemas(openApi, newCoproducts)
  }

  private def collectCandidates(
      openApi: SafeOpenApi,
      childToParent: Map[SchemaRef, SafeComposedSchema],
      collector: SafePathItem => List[(String, SafeRefSchema)]
  ) = {
    val responses = openApi.paths.values.flatMap(collector)
    val responsePerOperation = responses
      .groupBy(_._1)
      .mapValues(_.map(_._2).toList)
    val responseWithoutCommonParent = responsePerOperation
      .filter(_._2.size >= 2)
      .filterNot { case (_, errors) =>
        errors.flatMap(e => childToParent.get(e.ref)).toSet.size == 1
      }
    responseWithoutCommonParent
  }

  private def createCoproduct(
      operationId: String,
      errors: List[SafeSchema],
      postfix: String
  ) = {
    val unsafeNewSchema = new ComposedSchema
    unsafeNewSchema.setOneOf(errors.map(_.unsafe).asJava)
    s"${operationId.capitalize}$postfix" -> unsafeNewSchema
  }

  private def collectCoproducts(openApi: SafeOpenApi) =
    openApi.components
      .map(_.schemas)
      .getOrElse(Map.empty)
      .collect { case (k, v: SafeComposedSchema) =>
        k -> v
      }

  private def collectSuccessResponses(path: SafePathItem) =
    path.operations.values
      .flatMap(op =>
        op.collectResponses(statusCode => statusCode.isSuccess)
          .values
          .collect { case sr: SafeRefSchema => op.operationId -> sr }
      )
      .toList

  private def collectErrorResponses(path: SafePathItem) =
    path.operations.values
      .flatMap(op =>
        op.collectResponses(statusCode => statusCode.isClientError)
          .values
          .collect { case sr: SafeRefSchema => op.operationId -> sr }
      )
      .toList

  private def registerNewSchemas(
      openApi: SafeOpenApi,
      schemas: Map[String, ComposedSchema]
  ): SafeOpenApi = {
    openApi.components.foreach { cmp =>
      schemas.foreach { case (key, schema) =>
        cmp.unsafe.addSchemas(key, schema)
      }
    }
    openApi
  }
}
