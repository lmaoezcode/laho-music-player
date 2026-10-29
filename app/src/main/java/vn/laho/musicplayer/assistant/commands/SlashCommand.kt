// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.commands

import vn.laho.musicplayer.assistant.AssistantReply

/** Where a command writes its output: the assistant chat. */
interface ChatSink {
    /** Appends an assistant message and returns its position, for later [update]s. */
    fun post(text: String, source: AssistantReply.Source? = null): Int

    /** Replaces the message at [index] (progress lines). */
    fun update(index: Int, text: String)
}

/**
 * A developer/maintenance command typed in the chat, such as "/index" or "/drive login".
 * Commands run in a coroutine owned by the chat; "/stop" cancels it, so long-running commands
 * should catch [kotlinx.coroutines.CancellationException], post a note and rethrow.
 */
interface SlashCommand {
    /** Lower-case prefixes this command answers to, e.g. "/mood" (also matches "/mood export"). */
    val prefixes: List<String>

    /** Runs the command. [input] is the whole line, trimmed and lower-cased. */
    suspend fun run(input: String, chat: ChatSink)
}

/** Picks the [SlashCommand] for a chat line. Pure, so it is unit-tested. */
class CommandRouter(private val commands: List<SlashCommand>) {

    init {
        val all = commands.flatMap { it.prefixes }
        require(all.size == all.toSet().size) { "Duplicate command prefixes: $all" }
        require(all.all { it.startsWith("/") && it == it.lowercase() }) { "Prefixes must be lower-case and start with /" }
    }

    /** Returns the command and the normalised input, or null for a normal assistant request. */
    fun route(line: String): Pair<SlashCommand, String>? {
        val input = line.trim().lowercase()
        if (!input.startsWith("/")) return null
        val word = input.substringBefore(' ')
        val command = commands.firstOrNull { word in it.prefixes } ?: return null
        return command to input
    }

    /** All prefixes, for help text. */
    val prefixes: List<String> get() = commands.flatMap { it.prefixes }

    companion object {
        const val STOP = "/stop"
    }
}
