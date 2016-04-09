package io.buoyant.namerd.storage.experimental.kubernetes

import io.buoyant.k8s.{Client, NsThirdPartyVersion, ThirdPartyVersion}

case class Api(client: Client) extends ThirdPartyVersion[Dtab] {
  override def owner: String = Api.Owner

  override def ownerVersion: String = Api.OwnerVersion

  override def withNamespace(ns: String) = new NsApi(client, ns)
  implicit val descriptor = DtabDescriptor
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}

object Api {
  // TODO: should this be buoyant.io, linkerd.io, namerd.io...?
  val Owner = "l5d.io"

  // TODO: what versioning scheme do we want to use here? I assume we don't want
  //   to match linkerd/namerd versions, as this schema is unlikely to change with similar regularity.
  val OwnerVersion = "v1"
}

class NsApi(client: Client, ns: String)
  extends NsThirdPartyVersion[Dtab](client, Api.Owner, Api.OwnerVersion, ns) {
  implicit val descriptor = DtabDescriptor
  def dtabs = listResource[Dtab, DtabWatch, DtabList]()
}
