package com.longrange.dragcurve.sync

import java.io.IOException

data class RemoteMeta(val id: String, val version: String, val createdTime: String = "")

interface RemoteFile {
    fun list(): List<RemoteMeta>
    fun meta(id: String): RemoteMeta?
    fun download(id: String): String
    fun create(content: String): RemoteMeta
    fun update(id: String, content: String): RemoteMeta
    fun delete(id: String)
}

class RemoteAuthException(message: String) : Exception(message)

class RemoteHttpException(val code: Int, message: String) : IOException(message)
