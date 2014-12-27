package org.http4s.rho
package swagger

import org.http4s._
import org.http4s.Method._
import org.http4s.Header._
import org.http4s.rho.bits.QueryAST.EmptyQuery
import org.json4s._
import org.json4s.Extraction._
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods._
import org.specs2.mutable.Specification

import com.wordnik.swagger.model.{ Operation, Parameter }

package object dummy {
  sealed abstract class Renderable
  case class ModelA(name: String, color: Int) extends Renderable
  case class ModelB(name: String, id: Long) extends Renderable
  case class ModelC(name: String, shape: String) extends Renderable
}

class ApiBuilderSpec extends Specification {

  val builder = new ApiBuilder("0.1", DefaultSwaggerFormats)

  "ApiBuilder.generateNickname" should {
    "Generate a nickname without param" in {
      builder.generateNickname("/hello/world", GET) should_== "getHelloWorld"
    }
    "Generate a nickname with a param" in {
      builder.generateNickname("/hello/{world}", GET) should_== "getHello"
    }
    "Generate a nickname with a POST" in {
      builder.generateNickname("/hello", POST) should_== "postHello"
    }
  }

  "ApiBuilder.analyzeQuery" should {
    "Analyze a simple query" in {
      val q = param[Int]("id")
      builder.analyzeQuery(q.rule) should_==
        List(Parameter("id", None, None, true, false, "integer", paramType = "query"))
    }

    "Analyze a simple query with a default value" in {
      val q = param[Int]("id", 6)
      builder.analyzeQuery(q.rule) should_==
        List(Parameter("id", None, Some(6.toString), false, false, "integer", paramType = "query"))
    }

    "Analyze a query with two params" in {
      val q = param[Int]("id") & param[String]("str", "Hello")
      builder.analyzeQuery(q.rule) should_== List(
        Parameter("id", None, None, true, false, "integer", paramType = "query"),
        Parameter("str", None, Some("Hello"), false, false, "string", paramType = "query"))
    }

    "Deal with Query OR structure" in {
      val q = param[Int]("id") || param[Int]("id2")

      def orStr(str: String) = s"Optional if the following params are satisfied: [$str]"

      builder.analyzeQuery(q.rule) should_== List(
        Parameter("id", Some(orStr("id2")), None, true, false, "integer", paramType = "query"),
        Parameter("id2", Some(orStr("id")), None, true, false, "integer", paramType = "query"))
    }
  }

  "ApiBuilder.analyzeHeaders" should {
    "Analyze a single HeaderRule" in {
      val h = require(`Content-Length`)
      val p = Parameter("Content-Length", None, None, true, false, "string", paramType = "header")
      builder.analyzeHeaders(h.rule) should_== List(p)
    }

    "Analyze a two HeaderRules" in {
      val h = require(`Content-Length`) && require(`Content-MD5`)
      val p1 = Parameter("Content-Length", None, None, true, false, "string", paramType = "header")
      val p2 = Parameter("Content-MD5", None, None, true, false, "string", paramType = "header")
      builder.analyzeHeaders(h.rule) should_== List(p1, p2)
    }

    "Analyze OR HeaderRules" in {
      val h = require(`Content-Length`) || require(`Content-MD5`)

      def orStr(str: String) = s"Optional if the following headers are satisfied: [$str]"

      val p1 = Parameter("Content-Length", Some(orStr("Content-MD5")), None, true, false, "string", paramType = "header")
      val p2 = Parameter("Content-MD5", Some(orStr("Content-Length")), None, true, false, "string", paramType = "header")
      builder.analyzeHeaders(h.rule) should_== List(p1, p2)
    }
  }

  "ApiBuilder.collectPaths" should {
    val q = EmptyQuery
    def baseOp = Operation("GET", "", "", "void", "temp- will replace", 0)

    "find a simple path" in {
      val p = "foo"
      val List((path, op)) = builder.collectPaths(p.rule :: Nil, q, baseOp)
      (path should_== "/foo") && (op should_== baseOp)
    }

    "find a simple AND path" in {
      val p = "foo" / "bar"
      val List((path, op)) = builder.collectPaths(p.rule :: Nil, q, baseOp)
      (path should_== "/foo/bar") && (op should_== baseOp)
    }

    "find a simple OR path" in {
      val p = "foo" || "bar"
      val List((path1, op1), (path2, op2)) = builder.collectPaths(p.rule :: Nil, q, baseOp)
      (path1 should_== "/foo") && (op1 should_== baseOp) &&
        (path2 should_== "/bar") && (op2 should_== baseOp)
    }

    "find a capture OR path" in {
      val p = pathVar[Int]("foo") || pathVar[Int]("bar")
      val param1 = Parameter("foo", None, None, true, false, "integer", paramType = "path")
      val param2 = Parameter("bar", None, None, true, false, "integer", paramType = "path")
      val List((path1, op1), (path2, op2)) = builder.collectPaths(p.rule :: Nil, q, baseOp)
      (path1 should_== "/{foo}") && (op1.parameters.length should_== 1) && (op1.parameters.head should_== param1)
      (path2 should_== "/{bar}") && (op2.parameters.length should_== 1) && (op2.parameters.head should_== param2)
    }

    "find a simple path" in {
      val p = "foo"
      val List((path, op)) = builder.collectPaths(p.rule :: Nil, q, baseOp)
      (path should_== "/foo") && (op should_== baseOp)
    }

    "find a simple path with a capture" in {
      val path = "foo" / pathVar[Int]("number")
      val param = Parameter("number", None, None, true, false, "integer", paramType = "path")
      val List((pathstr, op)) = builder.collectPaths(path.rule :: Nil, q, baseOp)
      (pathstr should_== "/foo/{number}") &&
        (op.parameters.length should_== 1) &&
        (op.parameters.head should_== param)
    }
  }

  "ApiBuilder.getMeta" should {
    import DummyCompiler.compilerInstance
    "Get a route description" in {
      val r = "foo" ** GET / "bar" |>> { () => "" }

      builder.actionToApiListing(r)
        .head
        .apis
        .head
        .operations
        .head
        .summary should_== "foo"
    }
    "Get available models" in {
      import dummy._
      import org.http4s.rho.bits.ResponseGeneratorInstances._

      implicit val jsonFormats: Formats =
        DefaultFormats

      implicit def renderableEncoder[T <: Renderable]: EntityEncoder[T] =
        EntityEncoder
          .stringEncoder(Charset.`UTF-8`)
          .contramap { r: T => compact(render(decompose(r))) }
          .withContentType(`Content-Type`(MediaType.`application/json`, Charset.`UTF-8`))

      val r = "testing models" ** GET / "models" |>> { () =>
        val a = 0
        a match {
          case 0 => Ok(ModelA("modela", 1))
          case 1 => NotFound(ModelB("modelb", 2))
          case 2 => PreconditionFailed(ModelC("modelc", "round"))
        }
      }

      builder.actionToApiListing(r)
        .head
        .models
        .get
        .map(_._1) should_== List("ModelA", "ModelB", "ModelC")
    }
  }
}
