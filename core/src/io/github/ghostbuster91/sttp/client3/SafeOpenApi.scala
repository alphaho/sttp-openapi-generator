package io.github.ghostbuster91.sttp.client3

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Components
import scala.collection.JavaConverters._
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.parameters.PathParameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import io.swagger.v3.oas.models.parameters.CookieParameter
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media._

class SafeOpenApi(openApi: OpenAPI) {
  def components: Option[SafeComponents] =
    Option(openApi.getComponents()).map(new SafeComponents(_))
  def paths: Map[String, SafePathItem] =
    openApi
      .getPaths()
      .asScala
      .toMap
      .mapValues(item => new SafePathItem(item))
      .toMap
}

class SafeComponents(c: Components) {
  def schemas: Map[String, SafeSchema] =
    Option(c.getSchemas())
      .map(_.asScala.mapValues(SafeSchema.apply).toMap)
      .getOrElse(Map.empty)
}

class SafePathItem(p: PathItem) {
  def operations: Map[Method, SafeOperation] =
    List(
      Option(p.getGet).map(op => (Method.Get: Method) -> new SafeOperation(op)),
      Option(p.getPut()).map(op =>
        (Method.Put: Method) -> new SafeOperation(op)
      ),
      Option(p.getPost()).map(op =>
        (Method.Post: Method) -> new SafeOperation(op)
      )
    ).flatten.toMap
}

class SafeOperation(op: Operation) {
  def operationId: String = op.getOperationId()

  def parameters: List[SafeParameter] =
    Option(op.getParameters())
      .map(_.asScala.toList.map { p =>
        p match {
          case pp: PathParameter   => new SafePathParameter(pp)
          case cp: CookieParameter => new SafeCookieParameter(cp)
          case hp: HeaderParameter => new SafeHeaderParameter(hp)
          case qp: QueryParameter  => new SafeQueryParameter(qp)
        }
      })
      .getOrElse(List.empty)

  def responses: Map[String, SafeApiResponse] =
    Option(op.getResponses())
      .map(_.asScala.mapValues(r => new SafeApiResponse(r)).toMap)
      .getOrElse(Map.empty)

  def requestBody: Option[SafeRequestBody] =
    Option(op.getRequestBody()).map(new SafeRequestBody(_))
}

class SafeRequestBody(r: RequestBody) {
  def content: Map[String, SafeMediaType] =
    Option(r.getContent())
      .map(_.asScala.mapValues(v => new SafeMediaType(v)).toMap)
      .getOrElse(Map.empty)

  def required: Boolean = r.getRequired()
}

sealed abstract class SafeParameter(p: Parameter) {
  def name: String = p.getName()
  def schema: SafeSchema = SafeSchema(p.getSchema())
  def required: Boolean = p.getRequired()
}
class SafePathParameter(p: PathParameter) extends SafeParameter(p)
class SafeHeaderParameter(p: HeaderParameter) extends SafeParameter(p)
class SafeCookieParameter(p: CookieParameter) extends SafeParameter(p)
class SafeQueryParameter(p: QueryParameter) extends SafeParameter(p)
class SafeApiResponse(r: ApiResponse) {
  def content: Map[String, SafeMediaType] =
    Option(r.getContent())
      .map(_.asScala.mapValues(v => new SafeMediaType(v)).toMap)
      .getOrElse(Map.empty)
}

class SafeMediaType(m: MediaType) {
  def schema: SafeSchema = SafeSchema(m.getSchema())
}

sealed trait Method
object Method {
  case object Put extends Method
  case object Get extends Method
  case object Post extends Method
  case object Delete extends Method
  case object Head extends Method
  case object Patch extends Method
  //TODO trace, connect, option?
}

sealed abstract class SafeSchema(s: Schema[_]) {
  def enum: List[Any] =
    Option(s.getEnum()).map(_.asScala.toList).getOrElse(List.empty)
}
class SafeArraySchema(s: ArraySchema) extends SafeSchema(s) {
  def items: SafeSchema = SafeSchema(s.getItems())
}
class SafeBinarySchema(s: BinarySchema) extends SafeSchema(s)
class SafeBooleanSchema(s: BooleanSchema) extends SafeSchema(s)
class SafeByteArraySchema(s: ByteArraySchema) extends SafeSchema(s)
class SafeDateSchema(s: DateSchema) extends SafeSchema(s)
class SafeDateTimeSchema(s: DateTimeSchema) extends SafeSchema(s)
class SafeEmailSchema(s: EmailSchema) extends SafeSchema(s)
class SafeFileSchema(s: FileSchema) extends SafeSchema(s)
class SafeIntegerSchema(s: IntegerSchema) extends SafeSchema(s)
class SafeMapSchema(s: MapSchema) extends SafeSchema(s)
class SafeNumberSchema(s: NumberSchema) extends SafeSchema(s)
class SafeObjectSchema(s: ObjectSchema) extends SafeSchema(s) {
  def properties: Map[String, SafeSchema] = Option(
    s.getProperties().asScala.mapValues(SafeSchema.apply).toMap
  ).getOrElse(Map.empty)
  def requiredFields: List[String] =
    Option(s.getRequired()).map(_.asScala.toList).getOrElse(List.empty)
}
class SafePasswordSchema(s: PasswordSchema) extends SafeSchema(s)
class SafeStringSchema(s: StringSchema) extends SafeSchema(s)
class SafeUUIDSchema(s: UUIDSchema) extends SafeSchema(s)
class SafeRefSchema(s: Schema[_]) extends SafeSchema(s) {
  def ref: SchemaRef = SchemaRef.apply(s.get$ref)
}

object SafeSchema {
  def apply(s: Schema[_]): SafeSchema =
    s match {
      case as: ArraySchema                => new SafeArraySchema(as)
      case bs: BooleanSchema              => new SafeBooleanSchema(bs)
      case bas: ByteArraySchema           => new SafeByteArraySchema(bas)
      case ds: DateSchema                 => new SafeDateSchema(ds)
      case dts: DateTimeSchema            => new SafeDateTimeSchema(dts)
      case es: EmailSchema                => new SafeEmailSchema(es)
      case fs: FileSchema                 => new SafeFileSchema(fs)
      case is: IntegerSchema              => new SafeIntegerSchema(is)
      case ms: MapSchema                  => new SafeMapSchema(ms)
      case ns: NumberSchema               => new SafeNumberSchema(ns)
      case os: ObjectSchema               => new SafeObjectSchema(os)
      case ps: PasswordSchema             => new SafePasswordSchema(ps)
      case ss: StringSchema               => new SafeStringSchema(ss)
      case us: UUIDSchema                 => new SafeUUIDSchema(us)
      case other if other.get$ref != null => new SafeRefSchema(other)
    }
}