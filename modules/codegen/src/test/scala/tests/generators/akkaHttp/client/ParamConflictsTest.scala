package tests.generators.akkaHttp.client

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.meta._

import support.SwaggerSpecRunner

import dev.guardrail.Context
import dev.guardrail.generators.ProtocolDefinitions
import dev.guardrail.generators.scala.akkaHttp.AkkaHttp
import dev.guardrail.generators.scala.syntax.companionForStaticDefns
import dev.guardrail.generators.{ Client, Clients }
import dev.guardrail.terms.protocol.ClassDefinition

class ParamConflictsTest extends AnyFunSuite with Matchers with SwaggerSpecRunner {

  val swagger = s"""
    |swagger: "2.0"
    |info:
    |  title: Whatever
    |  version: 1.0.0
    |host: localhost:1234
    |paths:
    |  /foo:
    |    get:
    |      operationId: getFoo
    |      consumes:
    |        - application/x-www-form-urlencoded
    |      parameters:
    |        - in: formData
    |          name: conflicting_name
    |          type: string
    |          required: true
    |        - in: formData
    |          name: ConflictingName
    |          type: string
    |          required: true
    |      responses:
    |        200:
    |          description: Success
    |definitions:
    |  Foo:
    |    type: object
    |    properties:
    |      conflicting_name:
    |        type: string
    |      ConflictingName:
    |        type: string
    |""".stripMargin

  test("Generate non-conflicting names in clients") {
    val (
      _,
      Clients(Client(tags, className, _, _, cls, _) :: _, Nil),
      _
    ) = runSwaggerSpec(swagger)(Context.empty, AkkaHttp)

    val client = q"""
      class Client(host: String = "http://localhost:1234")(implicit httpClient: HttpRequest => Future[HttpResponse], ec: ExecutionContext, mat: Materializer) {
        val basePath: String = ""
        private[this] def makeRequest[T: ToEntityMarshaller](method: HttpMethod, uri: Uri, headers: scala.collection.immutable.Seq[HttpHeader], entity: T, protocol: HttpProtocol): EitherT[Future, Either[Throwable, HttpResponse], HttpRequest] = {
          EitherT(Marshal(entity).to[RequestEntity].map[Either[Either[Throwable, HttpResponse], HttpRequest]] {
            entity => Right(HttpRequest(method = method, uri = uri, headers = headers, entity = entity, protocol = protocol))
          }.recover({
            case t =>
              Left(Left(t))
          }))
        }
        def getFoo(conflicting_name: String, ConflictingName: String, headers: List[HttpHeader] = Nil): EitherT[Future, Either[Throwable, HttpResponse], GetFooResponse] = {
          val allHeaders = headers ++ scala.collection.immutable.Seq[Option[HttpHeader]]().flatten
          makeRequest(HttpMethods.GET, host + basePath + "/foo", allHeaders, FormData(List(List(("conflicting_name", Formatter.show(conflicting_name))), List(("ConflictingName", Formatter.show(ConflictingName)))).flatten: _*), HttpProtocols.`HTTP/1.1`).flatMap(req => EitherT(httpClient(req).flatMap(resp => resp.status match {
            case StatusCodes.OK =>
              resp.discardEntityBytes().future.map(_ => Right(GetFooResponse.OK))
            case _ =>
              FastFuture.successful(Left(Right(resp)))
          }).recover({
            case e: Throwable =>
              Left(Left(e))
          })))
        }
      }
    """

    cls.head.value.structure should equal(client.structure)
  }

  test("Generate non-conflicting names in definitions") {
    val (
      ProtocolDefinitions(ClassDefinition(_, _, _, cls, staticDefns, _) :: _, _, _, _, _),
      _,
      _
    )       = runSwaggerSpec(swagger)(Context.empty, AkkaHttp)
    val cmp = companionForStaticDefns(staticDefns)

    val definition = q"""
      case class Foo(conflicting_name: Option[String] = None, ConflictingName: Option[String] = None)
    """
    val companion  = q"""
      object Foo {
        implicit val encodeFoo: _root_.io.circe.Encoder.AsObject[Foo] = {
          val readOnlyKeys = _root_.scala.Predef.Set[_root_.scala.Predef.String]()
          _root_.io.circe.Encoder.AsObject.instance[Foo](a => _root_.io.circe.JsonObject.fromIterable(_root_.scala.Vector(("conflicting_name", a.conflicting_name.asJson), ("ConflictingName", a.ConflictingName.asJson)))).mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
        }
        implicit val decodeFoo: _root_.io.circe.Decoder[Foo] = new _root_.io.circe.Decoder[Foo] { final def apply(c: _root_.io.circe.HCursor): _root_.io.circe.Decoder.Result[Foo] = for (v0 <- c.downField("conflicting_name").as[Option[String]]; v1 <- c.downField("ConflictingName").as[Option[String]]) yield Foo(v0, v1) }
      }
    """

    cls.structure should equal(definition.structure)
    cmp.structure should equal(companion.structure)
  }
}
