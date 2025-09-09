package com.example.myapplication.network

import com.example.myapplication.model.C005Response
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface FoodSafetyApi {
    // C005 오픈API 호출 메서드 (BAR_CD를 path에 포함)
    @GET("{keyId}/{serviceId}/{dataType}/{startIdx}/{endIdx}/BAR_CD={barcode}")
    fun getProductByBarcode(
        @Path("keyId") keyId: String,
        @Path("serviceId") serviceId: String,
        @Path("dataType") dataType: String,
        @Path("startIdx") startIdx: Int,
        @Path("endIdx") endIdx: Int,
        @Path("barcode", encoded = true) barcode: String  // encoded 중요!
    ): Call<C005Response>
}
