package no.nav.sosialhjelp.upload.testutils

import HookType
import no.nav.sosialhjelp.upload.tusd.dto.FileInfo
import no.nav.sosialhjelp.upload.tusd.dto.FileMetadata
import no.nav.sosialhjelp.upload.tusd.dto.HTTPRequest
import no.nav.sosialhjelp.upload.tusd.dto.HookEvent
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import no.nav.sosialhjelp.upload.tusd.dto.LocalStorage
import no.nav.sosialhjelp.upload.tusd.dto.Storage

fun createHookRequest(
    type: HookType,
    fileInfo: FileInfo,
    event: HookEvent = createHookEvent(fileInfo),
) = HookRequest(type, event)

fun createHookEvent(
    fileInfo: FileInfo,
    httpRequest: HTTPRequest = createHTTPRequest(),
) = HookEvent(fileInfo, httpRequest)

fun createHTTPRequest(
    method: String = "post",
    uri: String = "whatever.com",
    remoteAddr: String = "",
    header: Map<String, List<String>> =
        mapOf("Authorization" to listOf("abcroflmao")),
): HTTPRequest = HTTPRequest(method, uri, remoteAddr, header)

fun createFileInfo(
    metadata: FileMetadata,
    id: String = "abc",
    size: Long = 10L,
    sizeIsDeferred: Boolean = true,
    offset: Long = 0L,
    isPartial: Boolean = false,
    isFinal: Boolean = true,
    partialUploads: List<String>? = null,
    storage: Storage = LocalStorage("path/to/file", "path/to/info"),
): FileInfo = FileInfo(id, size, sizeIsDeferred, offset, metadata, isPartial, isFinal, partialUploads, storage)

fun createFileMetadata(
    filename: String,
    externalId: String,
): FileMetadata = FileMetadata(filename, externalId)
