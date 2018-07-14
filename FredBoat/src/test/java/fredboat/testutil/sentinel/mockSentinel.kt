package fredboat.testutil.sentinel

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.entities.*
import fredboat.sentinel.GuildCache
import fredboat.sentinel.RawGuild
import fredboat.sentinel.RawMember
import fredboat.sentinel.RawVoiceChannel
import fredboat.testutil.sentinel.SentinelState.outgoing
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private lateinit var rabbit: RabbitTemplate
lateinit var guildCache: GuildCache

/** State of the fake Rabbit client */
object SentinelState {
    var guild = DefaultSentinelRaws.guild
    var banList = DefaultSentinelRaws.banList
    val outgoing = mutableMapOf<Class<*>, LinkedBlockingQueue<Any>>()
    private val log: Logger = LoggerFactory.getLogger(SentinelState::class.java)

    fun reset() {
        Thread.sleep(200) // Give messages a bit of time to come in - prevents race conditions
        log.info("Resetting sentinel state")

        guild = DefaultSentinelRaws.guild.copy()
        banList = DefaultSentinelRaws.banList
        outgoing.clear()
        guildCache.cache.remove(guild.id)
        //rabbit.convertAndSend(SentinelExchanges.EVENTS, GuildUpdateEvent(DefaultSentinelRaws.guild))
    }

    fun <T> poll(type: Class<T>, timeoutMillis: Long = 5000): T? {
        val queue = outgoing.getOrPut(type) { LinkedBlockingQueue() }
        @Suppress("UNCHECKED_CAST")
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) as? T
    }

    fun joinChannel(
            member: RawMember = DefaultSentinelRaws.owner,
            channel: RawVoiceChannel = DefaultSentinelRaws.musicChannel
    ) {
        val newList = guild.voiceChannels.toMutableList().apply {
            var removed: RawVoiceChannel? = null
            removeIf { removed = it; it.id == channel.id }
            val membersSet = removed?.members?.toMutableSet() ?: mutableListOf<Long>()
            membersSet.add(member.id)
            add(channel.copy(members = membersSet.toList()))
        }
        guild = guild.copy(voiceChannels = newList)
        guild = setMember(guild, member.copy(voiceChannel = channel.id))
        guildCache.get(guild.id).block(Duration.ofSeconds(4)) // Our event gets ignored if this is not cached and we time out
        rabbit.convertAndSend(SentinelExchanges.EVENTS, VoiceJoinEvent(
                DefaultSentinelRaws.guild.id,
                channel.id,
                member.id))

        log.info("Emulating ${member.name} joining ${channel.name}")
        delayUntil(timeout = 4000) { guildCache.getIfCached(guild.id)?.getMember(member.id)?.voiceChannel?.id == channel.id }
        if (guildCache.getIfCached(guild.id)?.getMember(member.id)?.voiceChannel?.id != channel.id) {
            val info = mapOf(
                    "guild" to guildCache.getIfCached(guild.id),
                    "member" to guildCache.getIfCached(guild.id)?.getMember(member.id),
                    "vc" to guildCache.getIfCached(guild.id)?.getMember(member.id)?.voiceChannel
            )
            throw RuntimeException("Failed to join VC. Debug info: $info")
        }
        log.info("${member.name} joined ${channel.name}")
    }

    private fun setMember(guild: RawGuild, member: RawMember): RawGuild {
        return guild.copy(members = guild.members.toMutableSet().apply { add(member) }.toList())
    }
}

@Service
@Suppress("MemberVisibilityCanBePrivate")
@RabbitListener(queues = [SentinelExchanges.REQUESTS], errorHandler = "rabbitListenerErrorHandler")
class MockSentinelRequestHandler(template: RabbitTemplate, cache: GuildCache) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(MockSentinelRequestHandler::class.java)
    }

    init {
        rabbit = template
        guildCache = cache
    }

    @RabbitHandler
    fun subscribe(request: GuildSubscribeRequest): RawGuild {
        default(request)
        log.info("Got subscription request")
        return SentinelState.guild
    }

    @RabbitHandler
    fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        default(request)
        log.info("FredBoat says: ${request.message}")
        return SendMessageResponse(Math.random().toLong())
    }

    @RabbitHandler
    fun editMessage(request: EditMessageRequest): SendMessageResponse {
        default(request)
        log.info("FredBoat edited: ${request.message}")
        return SendMessageResponse(request.messageId)
    }

    @RabbitHandler
    fun editMessage(request: BanListRequest): List<Ban> {
        default(request)
        return SentinelState.banList
    }

    @RabbitHandler
    fun guildPermissionRequest(request: GuildPermissionRequest): PermissionCheckResponse {
        default(request)

        /** Performs converse nonimplication */
        fun getMissing(expected: Long, actual: Long) = (expected.inv() or actual).inv()

        // This implementation is very limited, and only works for members without overrides
        val member = SentinelState.guild.members.find { it.id == request.member }!!
        var effective = 0L
        SentinelState.guild.roles.forEach {
            if (member.roles.contains(it.id)) {
                effective = it.permissions or effective
            }
        }
        return PermissionCheckResponse(
                effective = effective,
                missing = getMissing(request.rawPermissions, effective),
                missingEntityFault = false
        )

    }

    @RabbitHandler
    fun roleInfoRequest(request: RoleInfoRequest): RoleInfo {
        default(request)
        return when (request.id) {
            DefaultSentinelRaws.adminRole.id -> RoleInfo(request.id, 0, 0, false, false, false)
            DefaultSentinelRaws.uberAdminRole.id -> RoleInfo(request.id, 1, 0, false, false, false)
            else -> throw IllegalArgumentException()
        }
    }

    @RabbitHandler
    fun modRequest(request: ModRequest) {
        default(request)
        request.run {
            log.info("$type: $reason")
        }
    }

    @RabbitHandler(isDefault = true)
    fun default(request: Any): Any {
        val queue = outgoing.getOrPut(request.javaClass) { LinkedBlockingQueue() }
        queue.put(request)
        return ""
    }
}

