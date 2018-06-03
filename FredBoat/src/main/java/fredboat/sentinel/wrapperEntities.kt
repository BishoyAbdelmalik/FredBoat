package fredboat.sentinel

import com.fredboat.sentinel.entities.*
import fredboat.audio.lavalink.SentinelLavalink
import fredboat.config.property.AppConfig
import fredboat.perms.IPermissionSet
import fredboat.perms.NO_PERMISSIONS
import fredboat.perms.Permission
import fredboat.perms.PermissionSet
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.regex.Pattern
import java.util.stream.Stream
import kotlin.streams.toList

typealias RawGuild = com.fredboat.sentinel.entities.Guild
typealias RawMember = com.fredboat.sentinel.entities.Member
typealias RawUser = com.fredboat.sentinel.entities.User
typealias RawTextChannel = com.fredboat.sentinel.entities.TextChannel
typealias RawVoiceChannel = com.fredboat.sentinel.entities.VoiceChannel
typealias RawRole = com.fredboat.sentinel.entities.Role
typealias RawMessage = com.fredboat.sentinel.entities.Message

private val MEMBER_MENTION_PATTERN = Pattern.compile("<@!?([0-9]+)>", Pattern.DOTALL)
private val CHANNEL_MENTION_PATTERN = Pattern.compile("<#([0-9]+)>", Pattern.DOTALL)

@Service
private class WrapperEntityBeans(appConfigParam: AppConfig) {
    init {
        appConfig = appConfigParam
    }
}

private lateinit var appConfig: AppConfig

// TODO: These classes are rather inefficient. We should cache more things, and we should avoid duplication of Guild entities

class Guild(
        override val id: Long
) : SentinelEntity {
    val raw: RawGuild
        get() = sentinel.getGuild(id)
    val name: String
        get() = raw.name
    val owner: Member?
        get() {
            if (raw.owner != null) return Member(raw.owner!!)
            return null
        }

    // TODO: Make these lazy so we don't have to recompute them
    val textChannels: List<TextChannel>
        get() = raw.textChannels.map { TextChannel(it, id) }
    val voiceChannels: List<VoiceChannel>
        get() = raw.voiceChannels.map { VoiceChannel(it, id) }
    val voiceChannelsMap: Map<Long, VoiceChannel>
        get() = voiceChannels.associateBy { it.id }
    val selfMember: Member
        get() = membersMap[sentinel.getApplicationInfo().botId]!!
    val members: List<Member>
        get() = raw.members.map { Member(it.value) }
    val membersMap: Map<Long, Member>
        get() = members.associateBy { it.id }
    val roles: List<Role>
        get() = raw.roles.map { Role(it, id) }
    val shardId: Int
        get() = ((id shr 22) % appConfig.shardCount.toLong()).toInt()
    val shardString: String
        get() = "[$shardId/${appConfig.shardCount}]"
    val info: Mono<GuildInfo>
        get() = sentinel.getGuildInfo(this)

    /** This is true if we are present in this [Guild]*/
    val selfPresent: Boolean
        get() = true //TODO

    /** The routing key for the associated Sentinel */
    val routingKey: String
        get() = sentinel.tracker.getKey(shardId)

    fun getTextChannel(id: Long): TextChannel? {
        textChannels.forEach { if (it.id == id) return it }
        return null
    }

    fun getVoiceChannel(id: Long): VoiceChannel? {
        voiceChannels.forEach { if (it.id == id) return it }
        return null
    }

    fun getRole(id: Long): Role? {
        roles.forEach { if (it.id == id) return it }
        return null
    }

    override fun equals(other: Any?): Boolean {
        return other is Guild && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun getMember(userId: Long): Member? = membersMap[userId]
}

class Member(val raw: RawMember) : IMentionable, SentinelEntity {
    override val id: Long
        get() = raw.id
    val name: String
        get() = raw.name
    val nickname: String?
        get() = raw.nickname
    val effectiveName: String
        get() = if (raw.nickname != null) raw.nickname!! else raw.name
    val discrim: Short
        get() = raw.discrim
    val guild: Guild
        get() = Guild(raw.guildId)
    val guildId: Long
        get() = raw.guildId
    val isBot: Boolean
        get() = raw.bot
    val voiceChannel: VoiceChannel?
        get() {
            if (raw.voiceChannel != null) return guild.getVoiceChannel(raw.voiceChannel!!)
            return null
        }
    val roles: List<Role>
        get() {
            val list = mutableListOf<Role>()
            val guildRoles = guild.roles
            guildRoles.forEach { if (raw.roles.contains(it.id)) list.add(it) }
            return list.toList()
        }
    /** True if this [Member] is our bot */
    val isUs: Boolean
        get() = id == sentinel.getApplicationInfo().botId
    val avatarUrl: String
        get() = TODO("Not being sent by Sentinel yet")
    override val asMention: String
        get() = "<@$id>"
    val user: User
        get() = User(RawUser(
                id,
                name,
                discrim,
                isBot
        ))

    fun getPermissions(channel: Channel? = null): Mono<PermissionSet> {
        return when (channel) {
            null -> sentinel.checkPermissions(this, NO_PERMISSIONS)
                    .map { PermissionSet(it.effective) }
            else -> sentinel.checkPermissions(channel, this, NO_PERMISSIONS)
                    .map { PermissionSet(it.effective) }
        }
    }

    fun hasPermission(permissions: IPermissionSet, channel: Channel? = null): Mono<Boolean> {
        return when (channel) {
            null -> sentinel.checkPermissions(this, permissions)
                    .map { it.passed }
            else -> sentinel.checkPermissions(channel, this, permissions)
                    .map { it.passed }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is Member && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class User(val raw: RawUser) : IMentionable, SentinelEntity {
   override val id: Long
        get() = raw.id
    val name: String
        get() = raw.name
    val discrim: Short
        get() = raw.discrim
    val isBot: Boolean
        get() = raw.bot
    override val asMention: String
        get() = "<@$id>"

    fun sendPrivate(message: String) = sentinel.sendPrivateMessage(this, RawMessage(message))
    fun sendPrivate(message: IMessage) = sentinel.sendPrivateMessage(this, message)

    override fun equals(other: Any?): Boolean {
        return other is User && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class TextChannel(val raw: RawTextChannel, val guildId: Long) : Channel, IMentionable {
    override val id: Long
        get() = raw.id
    override val name: String
        get() = raw.name
    override val guild: Guild
        get() = Guild(guildId)
    override val ourEffectivePermissions: Long
        get() = raw.ourEffectivePermissions
    override val asMention: String
        get() = "<#$id>"

    fun send(str: String): Mono<SendMessageResponse> {
        return sentinel.sendMessage(guild.routingKey, this, RawMessage(str))
    }

    fun send(message: IMessage): Mono<SendMessageResponse> {
        return sentinel.sendMessage(guild.routingKey, this, message)
    }

    fun editMessage(messageId: Long, message: String): Mono<Unit> =
            sentinel.editMessage(this, messageId, RawMessage(message))

    fun editMessage(messageId: Long, message: IMessage): Mono<Unit> =
            sentinel.editMessage(this, messageId, message)

    fun deleteMessage(messageId: Long) = sentinel.deleteMessages(this, listOf(messageId))

    fun sendTyping() {
        sentinel.sendTyping(this)
    }

    fun canTalk() = checkOurPermissions(Permission.VOICE_CONNECT + Permission.VOICE_SPEAK)


    override fun equals(other: Any?): Boolean {
        return other is TextChannel && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class VoiceChannel(val raw: RawVoiceChannel, val guildId: Long) : Channel {
    override val id: Long
        get() = raw.id
    override val name: String
        get() = raw.name
    override val guild: Guild
        get() = Guild(guildId)
    override val ourEffectivePermissions: Long
        get() = raw.ourEffectivePermissions
    val userLimit: Int
        get() = 0 //TODO
    val members: List<Member>
        get() = listOf() //TODO: List of members

    override fun equals(other: Any?): Boolean {
        return other is VoiceChannel && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun connect() {
        SentinelLavalink.INSTANCE.getLink(guild).connect(this)
    }
}

class Role(val raw: RawRole, val guildId: Long) : IMentionable, SentinelEntity {
    override val id: Long
        get() = raw.id
    val name: String
        get() = raw.name
    val permissions: PermissionSet
        get() = PermissionSet(raw.permissions)
    val guild: Guild
        get() = Guild(guildId)
    val isPublicRole: Boolean // The @everyone role shares the ID of the guild
        get() = id == guildId
    override val asMention: String
        get() = "<@$id>"
    val info: Mono<RoleInfo>
        get() = sentinel.getRoleInfo(this)

    override fun equals(other: Any?): Boolean {
        return other is Role && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class Message(val raw: MessageReceivedEvent) : SentinelEntity {
    override val id: Long
        get() = raw.id
    val content: String
        get() = raw.content
    val member: Member
        get() = Member(raw.author)
    val guild: Guild
        get() = Guild(raw.guildId)
    val channel: TextChannel
        get() = TextChannel(raw.channel, raw.guildId)
    val mentionedMembers: List<Member>
    // Technically one could mention someone who isn't a member of the guild,
    // but we don't really care for that
        get() = MEMBER_MENTION_PATTERN.matcher(content)
                .results()
                .flatMap<Member> {
                    Stream.ofNullable(guild.getMember(it.group(1).toLong()))
                }
                .toList()
    val mentionedChannels: List<TextChannel>
        get() = CHANNEL_MENTION_PATTERN.matcher(content)
                .results()
                .flatMap<TextChannel> {
                    Stream.ofNullable(guild.getTextChannel(it.group(1).toLong()))
                }
                .toList()
    val attachments: List<String>
        get() = raw.attachments

    fun delete(): Mono<Unit> = sentinel.deleteMessages(channel, listOf(id))
}

