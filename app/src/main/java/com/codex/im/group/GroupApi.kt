package com.codex.im.group

import com.codex.im.storage.GroupInfo
import com.codex.im.storage.GroupMember
import com.codex.im.storage.GroupMemberRole
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface GroupApi {
    suspend fun createGroup(accessToken: String, name: String, memberUserIds: List<String>): GroupCreateResult

    suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult

    suspend fun groups(accessToken: String): GroupListResult

    suspend fun members(accessToken: String, groupId: String): GroupMembersResult
}

sealed class GroupCreateResult {
    data class Success(
        val group: GroupInfo,
        val members: List<GroupMember>
    ) : GroupCreateResult()

    data class Failure(val message: String) : GroupCreateResult()
}

sealed class GroupResult {
    data class Success(val group: GroupInfo) : GroupResult()

    data class Failure(val message: String) : GroupResult()
}

sealed class GroupListResult {
    data class Success(val groups: List<GroupInfo>) : GroupListResult()

    data class Failure(val message: String) : GroupListResult()
}

sealed class GroupMembersResult {
    data class Success(
        val group: GroupInfo,
        val members: List<GroupMember>
    ) : GroupMembersResult()

    data class Failure(val message: String) : GroupMembersResult()
}

class OkHttpGroupApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : GroupApi {
    override suspend fun createGroup(
        accessToken: String,
        name: String,
        memberUserIds: List<String>
    ): GroupCreateResult {
        return withContext(Dispatchers.IO) {
            val idsJson = memberUserIds.joinToString(",") { "\"${it.escapeJson()}\"" }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/groups")
                .header("Authorization", "Bearer $accessToken")
                .post(
                    """{"name":"${name.escapeJson()}","memberUserIds":[$idsJson]}"""
                        .toRequestBody(JSON)
                )
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext GroupCreateResult.Failure("HTTP ${response.code}")
                    }
                    GroupJsonParser.parseCreateGroup(body)
                }
            } catch (error: IOException) {
                GroupCreateResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                GroupCreateResult.Failure(error.message ?: "群组响应无效")
            }
        }
    }

    override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/groups/${groupId.escapePath()}")
                .header("Authorization", "Bearer $accessToken")
                .patch("""{"name":"${name.escapeJson()}"}""".toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext GroupResult.Failure("HTTP ${response.code}")
                    }
                    GroupJsonParser.parseGroup(body)
                }
            } catch (error: IOException) {
                GroupResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                GroupResult.Failure(error.message ?: "群组响应无效")
            }
        }
    }

    override suspend fun groups(accessToken: String): GroupListResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/groups")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext GroupListResult.Failure("HTTP ${response.code}")
                    }
                    GroupJsonParser.parseGroups(body)
                }
            } catch (error: IOException) {
                GroupListResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                GroupListResult.Failure(error.message ?: "群组响应无效")
            }
        }
    }

    override suspend fun members(accessToken: String, groupId: String): GroupMembersResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/groups/${groupId.escapePath()}/members")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext GroupMembersResult.Failure("HTTP ${response.code}")
                    }
                    GroupJsonParser.parseMembers(body)
                }
            } catch (error: IOException) {
                GroupMembersResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                GroupMembersResult.Failure(error.message ?: "群成员响应无效")
            }
        }
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun String.escapePath(): String {
        return replace("/", "")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

object GroupJsonParser {
    fun parseGroup(json: String): GroupResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return GroupResult.Failure(root.optionalString("message") ?: "群组请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            GroupResult.Success(payload.toGroupInfo())
        } catch (error: RuntimeException) {
            GroupResult.Failure(error.message ?: "群组响应无效")
        }
    }

    fun parseGroups(json: String): GroupListResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return GroupListResult.Failure(root.optionalString("message") ?: "群组请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            val groups = payload.optionalArray("groups")
                ?.map { element -> element.asJsonObject.toGroupInfo() }
                .orEmpty()
            GroupListResult.Success(groups)
        } catch (error: RuntimeException) {
            GroupListResult.Failure(error.message ?: "群组响应无效")
        }
    }

    fun parseCreateGroup(json: String): GroupCreateResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return GroupCreateResult.Failure(root.optionalString("message") ?: "创建群组失败")
            }
            val payload = root.optionalObject("data") ?: root
            val group = payload.toGroupInfo()
            GroupCreateResult.Success(
                group = group,
                members = payload.membersOrFallback(group)
            )
        } catch (error: RuntimeException) {
            GroupCreateResult.Failure(error.message ?: "群组响应无效")
        }
    }

    fun parseMembers(json: String): GroupMembersResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return GroupMembersResult.Failure(root.optionalString("message") ?: "获取群成员失败")
            }
            val payload = root.optionalObject("data") ?: root
            val group = payload.toGroupInfo()
            GroupMembersResult.Success(
                group = group,
                members = payload.membersOrFallback(group)
            )
        } catch (error: RuntimeException) {
            GroupMembersResult.Failure(error.message ?: "群成员响应无效")
        }
    }

    private fun JsonObject.toGroupInfo(): GroupInfo {
        val groupId = requiredString("groupId")
        return GroupInfo(
            groupId = groupId,
            name = optionalString("name") ?: "群聊",
            avatarUrl = optionalString("avatarUrl") ?: optionalString("avatar_url"),
            ownerId = requiredString("ownerId"),
            createdAt = optionalLong("createdAt") ?: 0L,
            updatedAt = optionalLong("updatedAt") ?: optionalLong("createdAt") ?: 0L,
            memberCount = optionalArray("memberUserIds")?.size() ?: 0
        )
    }

    private fun JsonObject.membersOrFallback(group: GroupInfo): List<GroupMember> {
        optionalArray("members")?.let { members ->
            return members.map { element -> element.asJsonObject.toGroupMember(group) }
        }
        return optionalArray("memberUserIds")
            ?.map { element -> element.asString }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.map { userId ->
                GroupMember(
                    groupId = group.groupId,
                    userId = userId,
                    displayName = userId,
                    avatarUrl = null,
                    role = if (userId == group.ownerId) GroupMemberRole.OWNER else GroupMemberRole.MEMBER,
                    joinedAt = group.createdAt,
                    updatedAt = group.updatedAt
                )
            }
            .orEmpty()
    }

    private fun JsonObject.toGroupMember(group: GroupInfo): GroupMember {
        val userId = requiredString("userId")
        return GroupMember(
            groupId = optionalString("groupId") ?: group.groupId,
            userId = userId,
            displayName = optionalString("displayName") ?: optionalString("nickname") ?: userId,
            avatarUrl = optionalString("avatarUrl") ?: optionalString("avatar_url"),
            role = optionalString("role")?.let { GroupMemberRole.valueOf(it) }
                ?: if (userId == group.ownerId) GroupMemberRole.OWNER else GroupMemberRole.MEMBER,
            joinedAt = optionalLong("joinedAt") ?: group.createdAt,
            updatedAt = optionalLong("updatedAt") ?: group.updatedAt
        )
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name) ?: error("Missing $name")
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.optionalArray(name: String): JsonArray? {
        val value: JsonElement = get(name) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else null
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else null
    }
}
