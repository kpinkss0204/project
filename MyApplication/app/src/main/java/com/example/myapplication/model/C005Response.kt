package com.example.myapplication.model

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

@Root(name = "C005Response", strict = false)
data class C005Response(
    @field:Element(name = "total_count", required = false)
    var totalCount: Int? = null,

    @field:ElementList(name = "row", inline = true, required = false)
    var rows: List<ProductInfo>? = null
)

@Root(name = "row", strict = false)
data class ProductInfo(
    @field:Element(name = "PRDLST_NM", required = false)
    var productName: String? = null,

    @field:Element(name = "POG_DAYCNT", required = false)
    var expiration: String? = null,

    @field:Element(name = "PRDLST_DCNM", required = false)
    var productCategory: String? = null,

    @field:Element(name = "BSSH_NM", required = false)
    var manufacturer: String? = null
)
