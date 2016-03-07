package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.Thrift.param
import io.buoyant.linkerd.Linker
import io.buoyant.router.Thrift.param.MethodInDst
import org.apache.thrift.protocol.TCompactProtocol
import org.scalatest.FunSuite

class ThriftInitializerTest extends FunSuite {

  test("valid thrift config") {
    val config = """
      |routers:
      |- protocol: thrift
      |  thriftMethodInDst: true
      |  client:
      |    thriftFramed: false
      |    thriftProtocol: binary
      |  servers:
      |  - thriftFramed: true
      |    thriftProtocol: compact
    """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftInitializer)).load(config)
    val router = linker.routers.head
    assert(router.params[MethodInDst].enabled)
    assert(!router.params[param.Framed].enabled)
    assert(!router.params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(router.servers.head.params[param.Framed].enabled)
    assert(router.servers.head.params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
  }

  test("unsupported thrift protocol") {
    val config = """
      |routers:
      |- protocol: thrift
      |  servers:
      |    thriftProtocol: magic
    """.stripMargin

    intercept[JsonMappingException] {
      Linker.Initializers(Seq(ThriftInitializer)).load(config)
    }
  }
}
