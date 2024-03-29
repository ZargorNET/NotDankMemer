/*
 * Developed by Turulix on 22.01.19 00:32.
 * Last modified 22.01.19 00:26.
 * Copyright (c) 2019. All rights reserved
 */

package com.jagrosh.jdautilities.command.impl;

import com.jagrosh.jdautilities.command.*;
import com.jagrosh.jdautilities.command.Command.Category;
import com.jagrosh.jdautilities.commons.utils.FixedSizeCache;
import com.jagrosh.jdautilities.commons.utils.SafeIdUtil;
import me.turulix.main.Database.Database;
import me.turulix.main.DiscordBot;
import me.turulix.main.i18n.I18nContext;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.ShutdownEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.core.hooks.EventListener;
import net.dv8tion.jda.core.requests.Requester;
import net.dv8tion.jda.core.utils.Checks;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An implementation of {@link com.jagrosh.jdautilities.command.CommandClient CommandClient} to be used by a bot.
 *
 * <p>This is a listener usable with {@link net.dv8tion.jda.core.JDA JDA}, as it implements
 * {@link net.dv8tion.jda.core.hooks.EventListener EventListener} in order to catch and use different kinds of {@link
 * net.dv8tion.jda.core.events.Event Event}s. The primary usage of this is where the CommandClient implementation takes
 * {@link net.dv8tion.jda.core.events.message.MessageReceivedEvent MessageReceivedEvent}s, and automatically processes
 * arguments, and provide them to a {@link com.jagrosh.jdautilities.command.Command Command} for running and execution.
 *
 * @author John Grosh (jagrosh)
 */
public class CommandClientImpl implements CommandClient, EventListener {
    private static final Logger LOG = LoggerFactory.getLogger(CommandClient.class);
    private static final int INDEX_LIMIT = 20;
    private static final String DEFAULT_PREFIX = "@mention";

    private final OffsetDateTime start;
    private final Game game;
    private final OnlineStatus status;
    private final String ownerId;
    private final String[] coOwnerIds;
    private final String prefix;
    private final String altprefix;
    private final String serverInvite;
    private final HashMap<String, Integer> commandIndex;
    private final ArrayList<Command> commands;
    private final String success;
    private final String warning;
    private final String error;
    private final String carbonKey;
    private final String botsKey;
    private final String botsOrgKey;
    private final HashMap<String, OffsetDateTime> cooldowns;
    private final HashMap<String, Integer> uses;
    private final FixedSizeCache<Long, Set<Message>> linkMap;
    private final boolean useHelp;
    private final Consumer<CommandEvent> helpConsumer;
    private final String helpWord;
    private final ScheduledExecutorService executor;
    private final AnnotatedModuleCompiler compiler;
    private final GuildSettingsManager manager;

    private String textPrefix;
    private CommandListener listener = null;
    private int totalGuilds;

    public CommandClientImpl(String ownerId, String[] coOwnerIds, String prefix, String altprefix, Game game, OnlineStatus status, String serverInvite, String success, String warning, String error, String carbonKey, String botsKey, String botsOrgKey, ArrayList<Command> commands, boolean useHelp, Consumer<CommandEvent> helpConsumer, String helpWord, ScheduledExecutorService executor, int linkedCacheSize, AnnotatedModuleCompiler compiler, GuildSettingsManager manager) {
        Checks.check(ownerId != null, "Owner ID was set null or not set! Please provide an User ID to register as the owner!");

        if (!SafeIdUtil.checkId(ownerId))
            LOG.warn(String.format("The provided Owner ID (%s) was found unsafe! Make sure ID is a non-negative long!", ownerId));

        if (coOwnerIds != null) {
            for (String coOwnerId : coOwnerIds) {
                if (!SafeIdUtil.checkId(coOwnerId))
                    LOG.warn(String.format("The provided CoOwner ID (%s) was found unsafe! Make sure ID is a non-negative long!", coOwnerId));
            }
        }

        this.start = OffsetDateTime.now();

        this.ownerId = ownerId;
        this.coOwnerIds = coOwnerIds;
        this.prefix = prefix == null || prefix.isEmpty() ? DEFAULT_PREFIX : prefix;
        this.altprefix = altprefix == null || altprefix.isEmpty() ? null : altprefix;
        this.textPrefix = prefix;
        this.game = game;
        this.status = status;
        this.serverInvite = serverInvite;
        this.success = success == null ? "" : success;
        this.warning = warning == null ? "" : warning;
        this.error = error == null ? "" : error;
        this.carbonKey = carbonKey;
        this.botsKey = botsKey;
        this.botsOrgKey = botsOrgKey;
        this.commandIndex = new HashMap<>();
        this.commands = new ArrayList<>();
        this.cooldowns = new HashMap<>();
        this.uses = new HashMap<>();
        this.linkMap = linkedCacheSize > 0 ? new FixedSizeCache<>(linkedCacheSize) : null;
        this.useHelp = useHelp;
        this.helpWord = helpWord == null ? "help" : helpWord;
        this.executor = executor == null ? Executors.newSingleThreadScheduledExecutor() : executor;
        this.compiler = compiler;
        this.manager = manager;
        this.helpConsumer = helpConsumer == null ? (event) -> {
            StringBuilder builder = new StringBuilder("**" + event.getSelfUser().getName() + "** commands:\n");
            Category category = null;
            for (Command command : commands) {
                if (!command.isHidden() && (!command.isOwnerCommand() || event.isOwner())) {
                    if (!Objects.equals(category, command.getCategory())) {
                        category = command.getCategory();
                        builder.append("\n\n  __").append(category == null ? "No Category" : category.getName()).append("__:\n");
                    }
                    builder.append("\n`").append(textPrefix).append(prefix == null ? " " : "").append(command.getName()).append(command.getArguments() == null ? "`" : " " + command.getArguments() + "`").append(" - ").append(command.getHelp());
                }
            }
            User owner = event.getJDA().getUserById(ownerId);
            if (owner != null) {
                builder.append("\n\nFor additional help, contact **").append(owner.getName()).append("**#").append(owner.getDiscriminator());
                if (serverInvite != null) builder.append(" or join ").append(serverInvite);
            }
            if (event.isFromType(ChannelType.TEXT)) event.reactSuccess();
            event.replyInDm(builder.toString(), unused -> {
            }, t -> event.replyWarning("Help cannot be sent because you are blocking Direct Messages."));
        } : helpConsumer;

        // Load commands
        for (Command command : commands) {
            addCommand(command);
        }
    }

    private static String[] splitOnPrefixLength(String rawContent, int length) {
        return Arrays.copyOf(rawContent.substring(length).trim().split("\\s+", 2), 2);
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public String getAltPrefix() {
        return altprefix;
    }

    @Override
    public String getTextualPrefix() {
        return textPrefix;
    }

    @Override
    public void addCommand(Command command) {
        addCommand(command, commands.size());
    }

    @Override
    public void addCommand(Command command, int index) {
        if (index > commands.size() || index < 0)
            throw new ArrayIndexOutOfBoundsException("Index specified is invalid: [" + index + "/" + commands.size() + "]");
        String name = command.getName();
        synchronized (commandIndex) {
            if (commandIndex.containsKey(name))
                throw new IllegalArgumentException("Command added has a name or alias that has already been indexed: \"" + name + "\"!");
            commandIndex.put(name, index);
            if (index < commands.size()) {
                commandIndex.keySet().stream().filter(key -> commandIndex.get(key) > index).collect(Collectors.toList()).forEach(key -> commandIndex.put(key, commandIndex.get(key) + 1));
            }
        }
        commands.add(index, command);
    }

    @Override
    public void removeCommand(String name) {
        if (!commandIndex.containsKey(name))
            throw new IllegalArgumentException("Name provided is not indexed: \"" + name + "\"!");
        int targetIndex = commandIndex.remove(name);
        if (commandIndex.containsValue(targetIndex)) {
            commandIndex.keySet().stream().filter(key -> commandIndex.get(key) == targetIndex).collect(Collectors.toList()).forEach(commandIndex::remove);
        }
        commandIndex.keySet().stream().filter(key -> commandIndex.get(key) > targetIndex).collect(Collectors.toList()).forEach(key -> commandIndex.put(key, commandIndex.get(key) - 1));
        commands.remove(targetIndex);
    }

    @Override
    public void addAnnotatedModule(Object module) {
        compiler.compile(module).forEach(this::addCommand);
    }

    @Override
    public void addAnnotatedModule(Object module, Function<Command, Integer> mapFunction) {
        compiler.compile(module).forEach(command -> addCommand(command, mapFunction.apply(command)));
    }

    @Override
    public CommandListener getListener() {
        return listener;
    }

    @Override
    public void setListener(CommandListener listener) {
        this.listener = listener;
    }

    @Override
    public List<Command> getCommands() {
        return commands;
    }

    @Override
    public OffsetDateTime getStartTime() {
        return start;
    }

    @Override
    public OffsetDateTime getCooldown(String name) {
        return cooldowns.get(name);
    }

    @Override
    public int getRemainingCooldown(String name) {
        if (cooldowns.containsKey(name)) {
            int time = (int) OffsetDateTime.now().until(cooldowns.get(name), ChronoUnit.SECONDS);
            if (time <= 0) {
                cooldowns.remove(name);
                return 0;
            }
            return time;
        }
        return 0;
    }

    @Override
    public void applyCooldown(String name, int seconds) {
        cooldowns.put(name, OffsetDateTime.now().plusSeconds(seconds));
    }

    @Override
    public void cleanCooldowns() {
        OffsetDateTime now = OffsetDateTime.now();
        cooldowns.keySet().stream().filter((str) -> (cooldowns.get(str).isBefore(now))).collect(Collectors.toList()).forEach(cooldowns::remove);
    }

    @Override
    public int getCommandUses(Command command) {
        return getCommandUses(command.getName());
    }

    @Override
    public int getCommandUses(String name) {
        return uses.getOrDefault(name, 0);
    }

    @Override
    public String getOwnerId() {
        return ownerId;
    }

    @Override
    public long getOwnerIdLong() {
        return Long.parseLong(ownerId);
    }

    @Override
    public String[] getCoOwnerIds() {
        return coOwnerIds;
    }

    @Override
    public long[] getCoOwnerIdsLong() {
        // Thought about using java.util.Arrays#setAll(T[], IntFunction<T>)
        // here, but as it turns out it's actually the same thing as this but
        // it throws an error if null. Go figure.
        if (coOwnerIds == null) return null;
        long[] ids = new long[coOwnerIds.length];
        for (int i = 0; i < ids.length; i++)
            ids[i] = Long.parseLong(coOwnerIds[i]);
        return ids;
    }

    @Override
    public String getSuccess() {
        return success;
    }

    @Override
    public String getWarning() {
        return warning;
    }

    @Override
    public String getError() {
        return error;
    }

    @Override
    public ScheduledExecutorService getScheduleExecutor() {
        return executor;
    }

    @Override
    public String getServerInvite() {
        return serverInvite;
    }

    @Override
    public int getTotalGuilds() {
        return totalGuilds;
    }

    @Override
    public String getHelpWord() {
        return helpWord;
    }

    @Override
    public boolean usesLinkedDeletion() {
        return linkMap != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <S> S getSettingsFor(Guild guild) {
        if (manager == null) return null;
        return (S) manager.getSettings(guild);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <M extends GuildSettingsManager> M getSettingsManager() {
        return (M) manager;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof MessageReceivedEvent) onMessageReceived((MessageReceivedEvent) event);

        else if (event instanceof GuildMessageDeleteEvent && usesLinkedDeletion())
            onMessageDelete((GuildMessageDeleteEvent) event);

        else if (event instanceof GuildJoinEvent) {
            if (((GuildJoinEvent) event).getGuild().getSelfMember().getJoinDate().plusMinutes(10).isAfter(OffsetDateTime.now()))
                sendStats(event.getJDA());
        } else if (event instanceof GuildLeaveEvent) sendStats(event.getJDA());
        else if (event instanceof ReadyEvent) onReady((ReadyEvent) event);
        else if (event instanceof ShutdownEvent) {
            GuildSettingsManager<?> manager = getSettingsManager();
            if (manager != null) manager.shutdown();
            executor.shutdown();
        }
    }

    private void onReady(ReadyEvent event) {
        if (!event.getJDA().getSelfUser().isBot()) {
            LOG.error("JDA-Utilities does not support CLIENT accounts.");
            event.getJDA().shutdown();
            return;
        }
        textPrefix = prefix.equals(DEFAULT_PREFIX) ? "@" + event.getJDA().getSelfUser().getName() + " " : prefix;
        event.getJDA().getPresence().setPresence(status == null ? OnlineStatus.ONLINE : status, game == null ? null : "default".equals(game.getName()) ? Game.playing("Type " + textPrefix + helpWord) : game);

        // Start SettingsManager if necessary
        GuildSettingsManager<?> manager = getSettingsManager();
        if (manager != null) manager.init();

        sendStats(event.getJDA());
    }

    private void onMessageReceived(MessageReceivedEvent event) {
        // Return if it's a bot
        if (event.getAuthor().isBot()) return;

        String[] parts = null;
        String rawContent = event.getMessage().getContentRaw();

        GuildSettingsProvider settings = event.isFromType(ChannelType.TEXT) ? provideSettings(event.getGuild()) : null;

        // Check for prefix or alternate prefix (@mention cases)
        if (prefix.equals(DEFAULT_PREFIX) || (altprefix != null && altprefix.equals(DEFAULT_PREFIX))) {
            if (rawContent.startsWith("<@" + event.getJDA().getSelfUser().getId() + ">") || rawContent.startsWith("<@!" + event.getJDA().getSelfUser().getId() + ">")) {
                parts = splitOnPrefixLength(rawContent, rawContent.indexOf(">") + 1);
            }
        }
        // Check for prefix
        if (parts == null && rawContent.toLowerCase().startsWith(prefix.toLowerCase()))
            if (DiscordBot.instance.registerStuff.database.guildSettingsDataManager.getSettings(event.getGuild()).getPrefix() == DiscordBot.instance.registerStuff.commandClient.getPrefix())
                parts = splitOnPrefixLength(rawContent, prefix.length());
        // Check for alternate prefix
        if (parts == null && altprefix != null && rawContent.toLowerCase().startsWith(altprefix.toLowerCase()))
            parts = splitOnPrefixLength(rawContent, altprefix.length());
        // Check for guild specific prefixes
        if (parts == null && settings != null) {
            Collection<String> prefixes = settings.getPrefixes();
            if (prefixes != null) {
                if (parts == null && settings != null && (prefixes = settings.getPrefixes()) != null) {
                    for (String prefix : prefixes) {
                        if (parts != null || !rawContent.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                        parts = CommandClientImpl.splitOnPrefixLength(rawContent, prefix.length());
                    }
                }
            }
        }


        if (parts != null) //starts with valid prefix
        {
            //TODO: Change command constructor for language.
            Database database = DiscordBot.instance.registerStuff.database;
            I18nContext context = new I18nContext(database.guildSettingsDataManager.getSettings(event.getGuild().getIdLong()), database.userManager.getUserSettings(event.getAuthor().getIdLong()));

            if (useHelp && parts[0].equalsIgnoreCase(context.get("helpWord"))) {
                CommandEvent cevent = new CommandEvent(event, parts[1] == null ? "" : parts[1], this);
                if (listener != null) listener.onCommand(cevent, null);
                helpConsumer.accept(cevent); // Fire help consumer
                if (listener != null) listener.onCompletedCommand(cevent, null);
                return; // Help Consumer is done
            } else if (event.isFromType(ChannelType.PRIVATE) || event.getTextChannel().canTalk()) {
                String name = parts[0];
                String args = parts[1] == null ? "" : parts[1];
                final Command command; // this will be null if it's not a command
                /*if (commands.size() < INDEX_LIMIT + 1)
                    command = commands.stream().filter(cmd -> cmd.isCommandFor(name, context)).findAny().orElse(null);
                else {
                    synchronized (commandIndex) {
                        int i = commandIndex.getOrDefault(name.toLowerCase(), -1);
                        command = i != -1 ? commands.get(i) : null;
                    }
                }*/

                //TODO: Maybe optimise this way??
                command = commands.stream().filter(cmd -> cmd.isCommandFor(name, context, null, null)).findFirst().orElse(null);

                if (command != null) {
                    CommandEvent cevent = new CommandEvent(event, args, this);

                    if (listener != null) listener.onCommand(cevent, command);
                    uses.put(command.getName(), uses.getOrDefault(command.getName(), 0) + 1);
                    List<Command> parents = new ArrayList<>();
                    parents.add(command);
                    command.run(cevent, null);
                    return; // Command is done
                }
            }
        }

        if (listener != null) listener.onNonCommandMessage(event);
    }

    private void sendStats(JDA jda) {
        OkHttpClient client = ((JDAImpl) jda).getHttpClient();

        if (carbonKey != null) {
            FormBody.Builder bodyBuilder = new FormBody.Builder().add("key", carbonKey).add("servercount", Integer.toString(jda.getGuilds().size()));

            if (jda.getShardInfo() != null) {
                bodyBuilder.add("shard_id", Integer.toString(jda.getShardInfo().getShardId())).add("shard_count", Integer.toString(jda.getShardInfo().getShardTotal()));
            }

            Request.Builder builder = new Request.Builder().post(bodyBuilder.build()).url("https://www.carbonitex.net/discord/data/botdata.php");

            client.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LOG.error("Failed to send information to carbonitex.net ", e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    LOG.info("Successfully send information to carbonitex.net");
                    response.close();
                }
            });
        }

        // Both bots.discord.pw and discordbots.org use the same JSON body
        // structure for POST requests to their stats APIs, so we reuse the same
        // JSON for both
        JSONObject body = new JSONObject().put("server_count", jda.getGuilds().size());
        if (jda.getShardInfo() != null) {
            body.put("shard_id", jda.getShardInfo().getShardId()).put("shard_count", jda.getShardInfo().getShardTotal());
        }

        if (botsOrgKey != null) {
            Request.Builder builder = new Request.Builder().post(RequestBody.create(Requester.MEDIA_TYPE_JSON, body.toString())).url("https://discordbots.org/api/bots/" + jda.getSelfUser().getId() + "/stats").header("Authorization", botsOrgKey).header("Content-Type", "application/json");

            client.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LOG.error("Failed to send information to discordbots.org ", e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    LOG.info("Successfully send information to discordbots.org");
                    response.close();
                }
            });
        }

        if (botsKey != null) {
            Request.Builder builder = new Request.Builder().post(RequestBody.create(Requester.MEDIA_TYPE_JSON, body.toString())).url("https://bots.discord.pw/api/bots/" + jda.getSelfUser().getId() + "/stats").header("Authorization", botsKey).header("Content-Type", "application/json");

            client.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LOG.error("Failed to send information to bots.discord.pw ", e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    LOG.info("Successfully send information to bots.discord.pw");
                    response.close();
                }
            });

            if (jda.getShardInfo() == null) {
                this.totalGuilds = jda.getGuilds().size();
            } else {
                Request.Builder b = new Request.Builder().get().url("https://bots.discord.pw/api/bots/" + jda.getSelfUser().getId() + "/stats").header("Authorization", botsKey).header("Content-Type", "application/json");

                client.newCall(b.build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        LOG.error("Failed to retrieve bot shard information from bots.discord.pw ", e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (Reader reader = response.body().charStream()) {
                            JSONArray array = new JSONObject(new JSONTokener(reader)).getJSONArray("stats");
                            int total = 0;
                            for (int i = 0; i < array.length(); i++)
                                total += array.getJSONObject(i).getInt("server_count");
                            totalGuilds = total;
                        } finally {
                            // Close the response
                            response.close();
                        }
                    }
                });

                // Good thing to keep in mind:
                // We used to make the request above by blocking the thread and waiting for DBots
                // to respond. For the future (should we succeed in not blocking that as well),
                // let's not do this again, okay?

                /*try(Reader reader = client.newCall(new Request.Builder()
                        .get().url("https://bots.discord.pw/api/bots/" + jda.getSelfUser().getId() + "/stats")
                        .header("Authorization", botsKey)
                        .header("Content-Type", "application/json")
                        .build()).execute().body().charStream()) {
                    JSONArray array = new JSONObject(new JSONTokener(reader)).getJSONArray("stats");
                    int total = 0;
                    for (int i = 0; i < array.length(); i++)
                        total += array.getJSONObject(i).getInt("server_count");
                    this.totalGuilds = total;
                } catch (Exception e) {
                    LOG.error("Failed to retrieve bot shard information from bots.discord.pw ", e);
                }*/
            }
        }
    }

    private void onMessageDelete(GuildMessageDeleteEvent event) {
        // We don't need to cover whether or not this client usesLinkedDeletion() because
        // that is checked in onEvent(Event) before this is even called.
        synchronized (linkMap) {
            if (linkMap.contains(event.getMessageIdLong())) {
                Set<Message> messages = linkMap.get(event.getMessageIdLong());
                if (messages.size() > 1 && event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_MANAGE))
                    event.getChannel().deleteMessages(messages).queue(unused -> {
                    }, ignored -> {
                    });
                else if (messages.size() > 0) messages.forEach(m -> m.delete().queue(unused -> {
                }, ignored -> {
                }));
            }
        }
    }

    private GuildSettingsProvider provideSettings(Guild guild) {
        Object settings = getSettingsFor(guild);
        if (settings != null && settings instanceof GuildSettingsProvider) return (GuildSettingsProvider) settings;
        else return null;
    }

    /**
     * <b>DO NOT USE THIS!</b>
     *
     * <p>This is a method necessary for linking a bot's response messages
     * to their corresponding call message ID.
     * <br><b>Using this anywhere in your code can and will break your bot.</b>
     *
     * @param callId  The ID of the call Message
     * @param message The Message to link to the ID
     */
    public void linkIds(long callId, Message message) {
        // We don't use linked deletion, so we don't do anything.
        if (!usesLinkedDeletion()) return;

        synchronized (linkMap) {
            Set<Message> stored = linkMap.get(callId);
            if (stored != null) stored.add(message);
            else {
                stored = new HashSet<>();
                stored.add(message);
                linkMap.add(callId, stored);
            }
        }
    }
}
