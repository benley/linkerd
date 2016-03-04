package io.buoyant.linkerd

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Stack, Dtab, NameTree, Path}
import io.buoyant.linkerd.config.Parser
import org.scalatest.FunSuite

class NamerInitializerTest extends FunSuite {

  def parse(config: String): NameInterpreter = {
    val mapper = Parser.objectMapper(config)
    TestNamerInitializer.registerSubtypes(mapper)
    val cfg = mapper.readValue[Seq[NamerConfig]](config)
    ConfiguredNamersInterpreter(cfg.map { c => c.prefix -> c.newNamer(Stack.Params.empty) })
  }

  val kind = "io.buoyant.linkerd.TestNamer"

  test(s"parse: $kind") {
    val yaml =
      s"""
- kind: $kind
"""
    val parsed = parse(yaml)
    val NameTree.Leaf(foo) = parsed.bind(Dtab.empty, Path.read("/foo/bar")).sample()
    assert(foo.id == Path.read("/foo"))
    assert(foo.path == Path.read("/bar"))

    assert(parsed.bind(Dtab.empty, Path.read("/foo/buh")).sample() == NameTree.Neg)
  }

  test("parse namer under another prefix") {
    val yaml =
      s"""
- kind: $kind
  prefix: /fuh
  buh: true
"""
    val parsed = parse(yaml)
    val NameTree.Leaf(buh) = parsed.bind(Dtab.empty, Path.read("/fuh/buh")).sample()
    assert(buh.id == Path.read("/fuh"))
    assert(buh.path == Path.read("/buh"))
  }

  test(s"falls back across NameTree.Neg") {
    val yaml =
      s"""
- kind: $kind
- kind: $kind
  buh: true
"""
    val parsed = parse(yaml)
    val NameTree.Leaf(buh) = parsed.bind(Dtab.empty, Path.read("/foo/buh")).sample()
    assert(buh.id == Path.read("/foo"))
    assert(buh.path == Path.read("/buh"))
  }

}
