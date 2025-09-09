package com.example.myapplication.network

import retrofit2.Retrofit
import retrofit2.converter.simplexml.SimpleXmlConverterFactory

object RetrofitClient {
    val instance: FoodSafetyApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://openapi.foodsafetykorea.go.kr/api/")
            .addConverterFactory(SimpleXmlConverterFactory.create())
            .build()
            .create(FoodSafetyApi::class.java)
    }
}
