package io.github.ghostbuster91.sttp.client3

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import scala.collection.JavaConverters._
import io.swagger.v3.parser.core.models.AuthorizationValue
import scala.meta._
import scala.collection.immutable

object Codegen {
  def generateUnsafe(openApiYaml: String): Source = {
    val openApi = loadOpenApi(openApiYaml)
    val schemas = openApi.components.map(_.schemas).getOrElse(Map.empty)
    val enums = EnumGenerator.generate(schemas)

    val modelClassNames = schemas.map { case (key, _) =>
      SchemaRef.fromKey(key) -> key
    }
    val modelGenerator = new ModelGenerator(schemas, modelClassNames)
    val model = modelGenerator.generate
    val operations = collectOperations(openApi, modelGenerator)
    source"""package io.github.ghostbuster91.sttp.client3.example {

          import _root_.sttp.client3._
          import _root_.sttp.model._
          import _root_.sttp.client3.circe._
          import _root_.io.circe.generic.auto._
          import _root_.java.io.File

          ..${enums.map(_.st)}
          ..${enums.map(_.companion)}
          ..${model.values.toList}

          class Api(baseUrl: String) {
            ..$operations
          }
        }
      """
  }

  private def collectOperations(
      openApi: SafeOpenApi,
      modelGenerator: ModelGenerator
  ) =
    openApi.paths.toList.flatMap { case (path, item) =>
      item.operations.map { case (method, operation) =>
        val uri = constructUrl(
          path,
          operation.parameters
        )
        val request = createRequestCall(method, uri)
        processOperation(
          operation,
          request,
          modelGenerator
        )
      }
    }

  private def constructUrl(path: String, params: List[SafeParameter]) = {
    val pathList =
      path
        .split("\\{[^/]*\\}")
        .toList
        .dropWhile(_ == "/")
    val queryParams = params.collect { case q: SafeQueryParameter =>
      Term.Name(q.name)
    }
    val querySegments = queryParams
      .foldLeft(List.empty[String]) { (acc, item) =>
        acc match {
          case list if list.nonEmpty => list :+ s"&$item="
          case immutable.Nil         => List(s"?$item=")
        }
      }

    val pathParams = params.collect { case p: SafePathParameter =>
      Term.Name(p.name)
    }
    val pathAndQuery = (pathList.dropRight(1) ++ List(
      pathList.lastOption.getOrElse("") ++ querySegments.headOption.getOrElse(
        ""
      )
    ) ++ querySegments.drop(1))
      .map(Lit.String(_))
    val pathAndQueryAdjusted = if (pathList.isEmpty && querySegments.isEmpty) {
      List(Lit.String(""))
    } else if (pathParams.isEmpty && queryParams.nonEmpty) {
      querySegments.map(Lit.String(_)) :+ Lit.String("")
    } else if (querySegments.isEmpty && pathParams.nonEmpty) {
      pathList.map(Lit.String(_)) :+ Lit.String("")
    } else if (querySegments.isEmpty && pathList.nonEmpty) {
      pathList.map(Lit.String(_))
    } else {
      pathAndQuery :+ Lit.String("")
    }

    Term.Interpolate(
      Term.Name("uri"),
      List(Lit.String("")) ++ pathAndQueryAdjusted,
      List(Term.Name("baseUrl")) ++ pathParams ++ queryParams
    )
  }

  private def createRequestCall(method: Method, uri: Term) =
    method match {
      case Method.Put    => q"basicRequest.put($uri)"
      case Method.Get    => q"basicRequest.get($uri)"
      case Method.Post   => q"basicRequest.post($uri)"
      case Method.Delete => q"basicRequest.delete($uri)"
      case Method.Head   => q"basicRequest.head($uri)"
      case Method.Patch  => q"basicRequest.patch($uri)"
    }

  private def processOperation(
      operation: SafeOperation,
      basicRequestWithMethod: Term,
      modelGenerator: ModelGenerator
  ): Defn.Def = {
    val operationId = operation.operationId

    val responseClassType = operation.responses.collectFirst {
      case ("200", response) =>
        response.content
          .collectFirst { case ("application/json", jsonResponse) =>
            jsonResponse.schema match {
              case rs: SafeRefSchema =>
                Type.Name(modelGenerator.classNameFor(rs.ref))
            }
          }
          .getOrElse(Type.Name("Unit"))
    }.head

    val functionName = Term.Name(operationId)
    val queryParameters = queryParameter(operation, modelGenerator)
    val pathParameters = pathParameter(operation, modelGenerator)
    val bodyParameter = requestBodyParameter(operation, modelGenerator)
    val parameters = pathParameters ++ queryParameters ++ bodyParameter
    val body: Term = Term.Apply(
      Term.Select(
        bodyParameter
          .map(p =>
            Term.Apply(
              Term.Select(basicRequestWithMethod, Term.Name("body")),
              List(Term.Name(p.name.value))
            )
          )
          .getOrElse(basicRequestWithMethod),
        Term.Name("response")
      ),
      List(q"asJson[$responseClassType].getRight")
    )
    q"def $functionName(..$parameters): Request[$responseClassType, Any] = $body"
  }

  private def pathParameter(
      operation: SafeOperation,
      modelGenerator: ModelGenerator
  ) =
    operation.parameters
      .collect { case pathParam: SafePathParameter =>
        val paramName = Term.Name(pathParam.name)
        val paramType = modelGenerator.schemaToType(
          "outerPathName",
          pathParam.name,
          pathParam.schema,
          pathParam.required
        )
        param"$paramName : $paramType"
      }

  private def queryParameter(
      operation: SafeOperation,
      modelGenerator: ModelGenerator
  ) =
    operation.parameters
      .collect { case queryParam: SafeQueryParameter =>
        val paramName = Term.Name(queryParam.name)
        val paramType = modelGenerator.schemaToType(
          "outerName",
          queryParam.name,
          queryParam.schema,
          queryParam.required
        )
        param"$paramName : $paramType"
      }

  private def requestBodyParameter(
      operation: SafeOperation,
      modelGenerator: ModelGenerator
  ) =
    operation.requestBody
      .flatMap { requestBody =>
        requestBody.content
          .collectFirst {
            case ("application/json", jsonRequest) =>
              jsonRequest.schema match {
                case rs: SafeRefSchema => modelGenerator.classNameFor(rs.ref)
              }
            case ("application/octet-stream", _) =>
              "File"
          }
          .map { requestClassName =>
            val paramName = Term.Name(s"a$requestClassName")
            val paramType = ModelGenerator.optionApplication(
              Type.Name(requestClassName),
              requestBody.required
            )
            param"$paramName : $paramType"
          }
      }

  private def loadOpenApi(yaml: String): SafeOpenApi = {
    val parser = new OpenAPIParser
    val opts = new ParseOptions()
    opts.setResolve(true)
    val parserResult = parser.readContents(
      yaml,
      List.empty[AuthorizationValue].asJava,
      opts
    )
    Option(parserResult.getMessages).foreach { messages =>
      messages.asScala.foreach(println)
    }
    Option(parserResult.getOpenAPI) match {
      case Some(spec) => new SafeOpenApi(spec)
      case None =>
        throw new RuntimeException(s"Failed to parse k8s swagger specs")
    }
  }

}

case class Enum(
    path: List[String],
    values: List[EnumValue],
    enumType: EnumType
) {
  def name: String = path.takeRight(2).map(_.capitalize).mkString
  def uncapitalizedName: String =
    name.take(1).toLowerCase() + name.drop(1)
}
case class EnumValue(rawValue: Any) {
  def name: String = rawValue.toString.capitalize
}
sealed trait EnumType
object EnumType {
  case object EString extends EnumType
  case object EInt extends EnumType
}
case class EnumDef(st: Defn.Trait, companion: Defn.Object)