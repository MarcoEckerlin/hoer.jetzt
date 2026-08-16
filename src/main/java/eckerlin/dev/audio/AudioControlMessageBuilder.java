package eckerlin.dev.audio;

import eckerlin.dev.commands.CommandHelper;
import eckerlin.dev.utils.Alert;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioControlMessageBuilder {

    private static final Color ACCENT = new Color(0x7CA9FF);
    private static final Color RADIO = new Color(0x7CD9A0);
    private static final Color PAUSIERT = new Color(0x8A8F98);
    private static final String BUTTON_PREFIX = "audio:";

    /**
     * Die laufende Nachricht je Server: Kanal und Nachricht.
     *
     * <p>Frueher hinterliess jeder Befehl seinen eigenen Block mit Titelbild,
     * Warteschlange und Knoepfen. Nach zehn Titeln bestand der Kanal nur noch
     * daraus. Jetzt gibt es <em>eine</em> Nachricht je Hoer-Sitzung, die
     * fortgeschrieben wird - und der Befehl selbst antwortet nur noch mit
     * einer Zeile.
     *
     * <p>Bewusst hier und nicht in einem Dienst: es ist reine Darstellung, und
     * der Zustand ist eine Nachrichten-ID. Geht er bei einem Neustart verloren,
     * entsteht beim naechsten Titel einfach eine neue Nachricht.
     */
    private static final Map<Long, long[]> SITZUNGEN = new ConcurrentHashMap<>();

    private AudioControlMessageBuilder() {
    }

    /**
     * Legt die Sitzungsnachricht an oder schreibt sie fort.
     *
     * <p>Steht die alte Nachricht nicht mehr (geloescht, Kanal gewechselt),
     * entsteht eine neue - deshalb der Fehlerzweig, der es erneut versucht.
     */
    public static void zeigeSitzung(
            Guild guild,
            MessageChannel kanal,
            String title,
            String description,
            PlayerState playerState
    ) {
        if (guild == null || kanal == null) {
            return;
        }

        MessageEmbed embed = buildEmbed(title, description, playerState);
        List<ActionRow> controls = buildControls(playerState);
        long[] sitzung = SITZUNGEN.get(guild.getIdLong());

        if (sitzung != null && sitzung[0] == kanal.getIdLong()) {
            kanal.editMessageEmbedsById(sitzung[1], embed)
                    .setComponents(controls)
                    .queue(
                            ignored -> {
                            },
                            ignored -> {
                                // Nachricht weg - dann eben eine neue.
                                SITZUNGEN.remove(guild.getIdLong());
                                sendeSitzung(guild, kanal, embed, controls);
                            }
                    );
            return;
        }

        sendeSitzung(guild, kanal, embed, controls);
    }

    private static void sendeSitzung(Guild guild, MessageChannel kanal, MessageEmbed embed, List<ActionRow> controls) {
        kanal.sendMessageEmbeds(embed)
                .addComponents(controls)
                .queue(
                        nachricht -> SITZUNGEN.put(guild.getIdLong(),
                                new long[]{kanal.getIdLong(), nachricht.getIdLong()}),
                        failure -> Alert.send("WARN", "AUDIO",
                                "Sitzungsnachricht konnte nicht gesendet werden: " + failure.getMessage())
                );
    }

    /**
     * Beendet die Sitzung: die Knoepfe verschwinden, die Nachricht bleibt als
     * Notiz stehen. Sie zu loeschen waere unhoeflich - sie ist der Verlauf.
     */
    public static void beendeSitzung(Guild guild, String schlusswort) {
        if (guild == null) {
            return;
        }
        long[] sitzung = SITZUNGEN.remove(guild.getIdLong());
        if (sitzung == null) {
            return;
        }

        MessageChannel kanal = guild.getJDA().getChannelById(MessageChannel.class, sitzung[0]);
        if (kanal == null) {
            return;
        }

        kanal.editMessageEmbedsById(sitzung[1], new EmbedBuilder()
                        .setTitle("Wiedergabe beendet")
                        .setDescription(schlusswort == null || schlusswort.isBlank() ? "-" : schlusswort)
                        .setColor(ACCENT)
                        .setTimestamp(Instant.now())
                        .build())
                .setComponents(List.of())
                .queue(ignored -> {
                }, ignored -> {
                });
    }

    public static void reply(
            SlashCommandInteractionEvent event,
            String title,
            String description,
            PlayerState playerState,
            boolean ephemeral
    ) {
        // Der Block mit Titelbild und Warteschlange gehoert in die
        // Sitzungsnachricht, nicht in jede einzelne Antwort.
        zeigeSitzung(event.getGuild(), event.getChannel(), title, description, playerState);

        String kurz = description == null || description.isBlank() ? "Erledigt." : description.trim();
        if (event.isAcknowledged()) {
            event.getHook().editOriginal(kurz).setComponents(List.of()).queue(ignored -> {
            }, ignored -> {
            });
            return;
        }

        event.reply(kurz).setEphemeral(ephemeral).queue(ignored -> {
        }, failure -> Alert.send("WARN", "AUDIO",
                "Audio-Antwort konnte nicht gesendet werden: " + failure.getMessage()));
    }

    public static void editDeferred(
            InteractionHook hook,
            String title,
            String description,
            PlayerState playerState
    ) {
        MessageEmbed embed = buildEmbed(title, description, playerState);
        List<ActionRow> controls = buildControls(playerState);

        hook.editOriginalEmbeds(embed)
                .setComponents(controls)
                .queue(
                        success -> {
                        },
                        failure -> {
                            Alert.send("WARN", "AUDIO", "Audio-Embed mit Buttons konnte nicht gesendet werden: " + failure.getMessage());
                            hook.editOriginalEmbeds(new EmbedBuilder()
                                            .setTitle(title == null || title.isBlank() ? "🎵 Audio" : title)
                                            .setDescription(description == null || description.isBlank() ? "-" : description)
                                            .setColor(ACCENT)
                                            .setTimestamp(Instant.now())
                                            .build())
                                    .setComponents(List.of())
                                    .queue(
                                            ignored -> {
                                            },
                                            ignored -> Alert.send("WARN", "AUDIO", "Auch der Audio-Fallback ohne Buttons konnte nicht gesendet werden.")
                                    );
                        }
                );
    }

    /**
     * Fuer bereits bestaetigte Commands: die Sitzungsnachricht traegt den
     * Block, die Antwort auf den Befehl bleibt eine Zeile.
     */
    public static void editDeferred(
            SlashCommandInteractionEvent event,
            String title,
            String description,
            PlayerState playerState
    ) {
        zeigeSitzung(event.getGuild(), event.getChannel(), title, description, playerState);

        String kurz = description == null || description.isBlank() ? "Erledigt." : description.trim();
        event.getHook().editOriginal(kurz).setComponents(List.of()).queue(ignored -> {
        }, ignored -> {
        });
    }

    /**
     * Die Sitzungsnachricht.
     *
     * <p>Aufbau bewusst flach statt in Feldern: Discord setzt zwei Felder
     * nebeneinander, und was dort landete - Modus, Zustand, Repeat, Bass,
     * Kanal, Queue, Lautstaerke, jedes mit eigenem Symbol - las sich als
     * Symbolteppich, in dem nichts mehr heraussticht. Jetzt gibt es vier
     * Ebenen mit klarer Rangfolge:</p>
     *
     * <ol>
     *   <li>Autorenzeile: was gerade passiert (spielt / pausiert / Radio)</li>
     *   <li>Titel: der Titel, verlinkt</li>
     *   <li>Beschreibung: Interpret und Laenge, darunter eine Statuszeile</li>
     *   <li>Fusszeile: die letzte Aktion mit Uhrzeit</li>
     * </ol>
     *
     * <p>In der Statuszeile steht nur, was vom Normalfall abweicht. "Repeat:
     * Aus" ist keine Information - der Knopf darunter sagt dasselbe, und wer
     * es nicht eingeschaltet hat, muss es nicht lesen.</p>
     */
    public static MessageEmbed buildEmbed(String title, String description, PlayerState playerState) {
        String normalizedTitle = title == null ? "" : title.trim();
        TrackView track = playerState == null ? null : playerState.currentTrack();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(farbe(playerState))
                .setTimestamp(Instant.now());

        if (track == null) {
            return embed
                    .setAuthor("Nichts läuft")
                    .setTitle(normalizedTitle.isBlank() ? "Bereit" : normalizedTitle)
                    .setDescription("Starte etwas mit `/play` oder `/radio` — oder über das Dashboard.")
                    .setFooter(fusszeile(description))
                    .build();
        }

        embed.setAuthor(kopfzeile(playerState));
        embed.setTitle(safe(track.title()));
        if (track.uri() != null && !track.uri().isBlank()) {
            embed.setUrl(track.uri());
        }
        embed.setDescription(beschreibung(playerState, track));

        // Immer als Vorschaubild. Ein grosses Titelbild schob alles Lesbare
        // aus dem Blick - genau das war das Problem an der alten Fassung.
        if (track.artworkUrl() != null && !track.artworkUrl().isBlank()) {
            embed.setThumbnail(track.artworkUrl());
        }

        embed.setFooter(fusszeile(description));
        return embed.build();
    }

    /** Grau wenn pausiert, sonst der Akzent - auf einen Blick erkennbar. */
    private static Color farbe(PlayerState playerState) {
        if (playerState != null && playerState.paused()) {
            return PAUSIERT;
        }
        if (playerState != null && playerState.playingRadio()) {
            return RADIO;
        }
        return ACCENT;
    }

    private static String kopfzeile(PlayerState playerState) {
        if (playerState.paused()) {
            return "⏸ Pausiert";
        }
        return playerState.playingRadio() ? "📻 Radio läuft" : "▶ Spielt gerade";
    }

    /**
     * Interpret und Laenge, darunter Kanal, Lautstaerke und was sonst noch an
     * ist. Getrennt durch Mittelpunkte statt durch Zeilenumbrueche mit
     * Symbolen: das ergibt eine Zeile zum Ueberfliegen statt einer Liste zum
     * Abarbeiten.
     */
    private static String beschreibung(PlayerState playerState, TrackView track) {
        StringBuilder text = new StringBuilder();
        text.append(safe(track.author()));

        String dauer = CommandHelper.formatDuration(track.durationMs(), track.stream());
        if (dauer != null && !dauer.isBlank() && !"-".equals(dauer)) {
            text.append(" · ").append(dauer);
        }

        List<String> status = new ArrayList<>();
        String kanal = playerState.voiceChannelName();
        if (kanal != null && !kanal.isBlank()) {
            status.add("🔊 " + kanal);
        }
        status.add(playerState.volume() + " %");
        // Nur Eingeschaltetes. Ein "Aus" kostet Platz und sagt nichts.
        if (playerState.repeatEnabled()) {
            status.add("🔁 Repeat");
        }
        if (playerState.bassBoostEnabled()) {
            status.add("🎚 Bass");
        }
        text.append("\n\n").append(String.join(" · ", status));

        text.append('\n').append(warteschlange(playerState));
        return text.toString();
    }

    /**
     * Eine Zahl allein beantwortet die Frage nicht, die man beim Blick auf die
     * Warteschlange hat. Deshalb steht hier, was als Naechstes kommt.
     */
    private static String warteschlange(PlayerState playerState) {
        List<TrackView> queue = playerState.queue();
        if (queue == null || queue.isEmpty()) {
            return "Warteschlange leer";
        }

        TrackView naechster = queue.get(0);
        String rest = queue.size() > 1 ? "  (+" + (queue.size() - 1) + " weitere)" : "";
        return "Als Nächstes: " + kurz(safe(naechster.title()), 60) + rest;
    }

    private static String kurz(String text, int laenge) {
        if (text == null) {
            return "";
        }
        return text.length() <= laenge ? text : text.substring(0, laenge - 1).trim() + "…";
    }

    /**
     * Die letzte Aktion gehoert in die Fusszeile, nicht ueber den Titel.
     *
     * <p>Vorher stand sie als erste Zeile der Beschreibung und schob bei jedem
     * Befehl den eigentlichen Inhalt nach unten. Unten steht sie ruhig, klein
     * und mit Uhrzeit daneben - als Notiz, was zuletzt geschah.
     */
    private static String fusszeile(String description) {
        String text = description == null ? "" : description.trim();
        if (text.isBlank()) {
            return "Steuerung über die Knöpfe";
        }
        return kurz(text, 120);
    }

    /**
     * Vier Knoepfe statt fuenf.
     *
     * <p>Pause und Fortsetzen waren zwei Knoepfe, von denen immer genau einer
     * ausgegraut dastand - eine Reihe, in der ein Fuenftel des Platzes fuer
     * etwas draufging, das man ohnehin nicht druecken kann. Jetzt ist es ein
     * Umschalter, der zeigt, was als Naechstes passiert.
     *
     * <p>Der Repeat-Knopf trug seinen Zustand im Text ("Repeat an" / "Repeat
     * aus") - und ob das den Zustand meinte oder die Wirkung des Drucks, war
     * nicht zu erkennen. Der Zustand steckt jetzt in der Farbe: gruen heisst
     * an.
     */
    public static List<ActionRow> buildControls(PlayerState playerState) {
        String guildId = playerState == null || playerState.guildId() == null ? "0" : playerState.guildId();
        boolean hatTitel = playerState != null && playerState.currentTrack() != null;
        boolean pausiert = playerState != null && playerState.paused();
        boolean repeat = playerState != null && playerState.repeatEnabled();

        Button umschalter = pausiert
                ? Button.success(componentId("resume", guildId), "Fortsetzen")
                        .withEmoji(Emoji.fromUnicode("▶️"))
                : Button.secondary(componentId("pause", guildId), "Pause")
                        .withEmoji(Emoji.fromUnicode("⏸️"));

        Button weiter = Button.secondary(componentId("skip", guildId), "Nächster")
                .withEmoji(Emoji.fromUnicode("⏭️"));

        Button wiederholen = repeat
                ? Button.success(componentId("repeat", guildId), "Repeat")
                        .withEmoji(Emoji.fromUnicode("🔁"))
                : Button.secondary(componentId("repeat", guildId), "Repeat")
                        .withEmoji(Emoji.fromUnicode("🔁"));

        Button stopp = Button.danger(componentId("stop", guildId), "Stop")
                .withEmoji(Emoji.fromUnicode("⏹️"));

        if (!hatTitel) {
            umschalter = umschalter.asDisabled();
            weiter = weiter.asDisabled();
            wiederholen = wiederholen.asDisabled();
            stopp = stopp.asDisabled();
        }

        return List.of(ActionRow.of(umschalter, weiter, wiederholen, stopp));
    }

    public static String componentId(String action, String guildId) {
        return BUTTON_PREFIX + action + ":" + guildId;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "Unbekannt";
        }
        return value.length() > 1024 ? value.substring(0, 1021) + "..." : value;
    }

}
