package io.github.ghostbuster91.sttp.client3.openapi

import io.github.ghostbuster91.sttp.client3.FileLoader
import io.swagger.v3.core.util.Yaml
import io.circe.yaml.parser
import utest._

object DynamicCoproductTest extends TestSuite {
  val tests = Tests {
    "multiple_errors" - test()
  }

  def test()(implicit testPath: utest.framework.TestPath) = {
    val expected = load(s"${testPath.value.mkString("/")}_expected.yaml")
    val input = load(s"${testPath.value.mkString("/")}.yaml")
    val actual = Yaml.pretty(OpenApiLoader.load(input).unsafe)
    assert(parser.parse(actual) == parser.parse(expected))
  }

  private def load(fileName: String): String =
    FileLoader.loadFile("openapi/dynamic_coproduct", fileName)

}
