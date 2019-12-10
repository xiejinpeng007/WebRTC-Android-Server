package com.e.androidwebrtc_sample_kt

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Type

val globalMoshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

inline fun <reified T> String.jsonToList(): List<T>? =
    ModelUtil.jsonToList(this, T::class.java)

inline fun <reified T> String?.fromJson(moshi: Moshi = globalMoshi): T? =
    this?.let { ModelUtil.fromJson(this, T::class.java, moshi = moshi) }



object ModelUtil {
    fun <T> jsonToList(json: String, classOfT: Class<T>, moshi: Moshi = globalMoshi): List<T>? {
        val type = Types.newParameterizedType(List::class.java, classOfT)
        val adapter = moshi.adapter<List<T>>(type)
        return adapter.fromJson(json)
    }

    fun <T> fromJson(json: String, classOfT: Class<T>, moshi: Moshi = globalMoshi): T? {
        return moshi.adapter(classOfT).fromJson(json)
    }

    fun <T> fromJson(json: String, typeOfT: Type, moshi: Moshi = globalMoshi): T? {
        return moshi.adapter<T>(typeOfT).fromJson(json)
    }
}