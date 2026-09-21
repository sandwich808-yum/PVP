package com.example.pvpconsent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PvPConsent - players can only damage each other after one challenges the
 * other with /pvp <player> and the other accepts.
 */
public final class PvPConsentPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ----- Settings (change these and rebuild) -----
    private static final int INVITE_TIMEOUT_SECONDS = 60;
    private static final int COUNTDOWN_SECONDS = 10;

    /** target UUID -> (challenger UUID -> expiry time in millis). Ordered so "latest invite" is last. */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();

    /** Both players of a duel map to the same Duel object. */
    private final Map<UUID, Duel> duels = new HashMap<>();

    private static final class Duel {
        final UUID a;
        final UUID b;
        boolean active = false; // false during countdown, true once "FIGHT!" happens
        BukkitTask countdown;

        Duel(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }

        boolean has(UUID id) {
            return a.equals(id) || b.equals(id);
        }

        UUID other(UUID id) {
            return a.equals(id) ? b : a;
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("pvp", "pvpaccept", "pvprefuse")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Duel duel : new ArrayList<>(duels.values())) {
            if (duel.countdown != null) {
                duel.countdown.cancel();
            }
        }
        duels.clear();
        invites.clear();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "pvp" -> sendInvite(player, args);
            case "pvpaccept" -> respond(player, args, true);
            case "pvprefuse" -> respond(player, args, false);
            default -> { }
        }
        return true;
    }

    private void sendInvite(Player sender, String[] args) {
        if (args.length != 1) {
            msg(sender, "Usage: /pvp <player>", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg(sender, "That player isn't online.", NamedTextColor.RED);
            return;
        }
        if (target.equals(sender)) {
            msg(sender, "You can't challenge yourself.", NamedTextColor.RED);
            return;
        }
        if (duels.containsKey(sender.getUniqueId())) {
            msg(sender, "You're already in a duel.", NamedTextColor.RED);
            return;
        }
        if (duels.containsKey(target.getUniqueId())) {
            msg(sender, target.getName() + " is already in a duel.", NamedTextColor.RED);
            return;
        }

        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();
        long now = System.currentTimeMillis();

        Map<UUID, Long> pending = invites.computeIfAbsent(targetId, k -> new LinkedHashMap<>());
        Long existing = pending.get(senderId);
        if (existing != null && existing > now) {
            msg(sender, "You already challenged " + target.getName() + ". Wait for their answer.", NamedTextColor.RED);
            return;
        }
        pending.put(senderId, now + INVITE_TIMEOUT_SECONDS * 1000L);

        msg(sender, "Challenge sent to " + target.getName() + ". It expires in "
                + INVITE_TIMEOUT_SECONDS + " seconds.", NamedTextColor.GREEN);

        Component invite = Component.text()
                .append(prefix())
                .append(Component.text(sender.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" challenged you to a PvP duel!  ", NamedTextColor.GRAY))
                .append(button("[ACCEPT]", NamedTextColor.GREEN, "/pvpaccept " + sender.getName(),
                        "Click to accept the duel"))
                .append(Component.space())
                .append(button("[REFUSE]", NamedTextColor.RED, "/pvprefuse " + sender.getName(),
                        "Click to refuse the duel"))
                .build();
        target.sendMessage(invite);
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);

        // Expire the invite after the timeout
        Bukkit.getScheduler().runTaskLater(this, () -> expireInvite(senderId, targetId),
                INVITE_TIMEOUT_SECONDS * 20L);
    }

    private void expireInvite(UUID senderId, UUID targetId) {
        Map<UUID, Long> pending = invites.get(targetId);
        if (pending == null) {
            return;
        }
        Long expiry = pending.get(senderId);
        // Only remove it if it's really the old invite (a newer one would have a later expiry)
        if (expiry != null && expiry - System.currentTimeMillis() < 1000L) {
            pending.remove(senderId);
            Player sender = Bukkit.getPlayer(senderId);
            Player target = Bukkit.getPlayer(targetId);
            if (sender != null) {
                msg(sender, "Your PvP challenge to " + (target != null ? target.getName() : "that player")
                        + " expired.", NamedTextColor.GRAY);
            }
        }
    }

    private void respond(Player target, String[] args, boolean accept) {
        Map<UUID, Long> pending = invites.get(target.getUniqueId());
        long now = System.currentTimeMillis();
        if (pending != null) {
            pending.values().removeIf(expiry -> expiry < now);
        }
        if (pending == null || pending.isEmpty()) {
            msg(target, "You have no pending PvP challenges.", NamedTextColor.RED);
            return;
        }

        UUID senderId;
        if (args.length >= 1) {
            Player named = Bukkit.getPlayerExact(args[0]);
            if (named == null || !pending.containsKey(named.getUniqueId())) {
                msg(target, "You have no challenge from that player.", NamedTextColor.RED);
                return;
            }
            senderId = named.getUniqueId();
        } else {
            UUID last = null;
            for (UUID id : pending.keySet()) {
                last = id; // most recent invite is the last one
            }
            senderId = last;
        }

        pending.remove(senderId);
        Player sender = Bukkit.getPlayer(senderId);
        if (sender == null) {
            msg(target, "That player went offline.", NamedTextColor.RED);
            return;
        }

        if (!accept) {
            msg(target, "You refused " + sender.getName() + "'s challenge.", NamedTextColor.GRAY);
            msg(sender, target.getName() + " refused your PvP challenge.", NamedTextColor.RED);
            return;
        }

        if (duels.containsKey(target.getUniqueId()) || duels.containsKey(senderId)) {
            msg(target, "One of you is already in a duel.", NamedTextColor.RED);
            return;
        }
        startDuel(sender, target);
    }

    // ------------------------------------------------------------------
    // Duel + countdown
    // ------------------------------------------------------------------

    private void startDuel(Player first, Player second) {
        UUID idA = first.getUniqueId();
        UUID idB = second.getUniqueId();
        Duel duel = new Duel(idA, idB);
        duels.put(idA, duel);
        duels.put(idB, duel);

        // Drop any other pending invites involving these two
        invites.remove(idA);
        invites.remove(idB);
        for (Map<UUID, Long> map : invites.values()) {
            map.remove(idA);
            map.remove(idB);
        }

        Component accepted = Component.text()
                .append(prefix())
                .append(Component.text("Duel accepted! PvP starts in " + COUNTDOWN_SECONDS + " seconds.",
                        NamedTextColor.GREEN))
                .build();
        first.sendMessage(accepted);
        second.sendMessage(accepted);

        duel.countdown = new BukkitRunnable() {
            int remaining = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                Player pa = Bukkit.getPlayer(duel.a);
                Player pb = Bukkit.getPlayer(duel.b);
                if (pa == null || pb == null) {
                    cancel(); // the quit listener cleans up the duel
                    return;
                }

                if (remaining > 0) {
                    float pitch = 0.6f + (COUNTDOWN_SECONDS - remaining) * 0.08f; // rings get higher
                    NamedTextColor color = remaining <= 3 ? NamedTextColor.RED : NamedTextColor.YELLOW;
                    for (Player p : new Player[]{pa, pb}) {
                        p.showTitle(Title.title(
                                Component.text(String.valueOf(remaining), color, TextDecoration.BOLD),
                                Component.text("PvP starts soon", NamedTextColor.GRAY),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, pitch);
                    }
                    remaining--;
                } else {
                    duel.active = true;
                    for (Player p : new Player[]{pa, pb}) {
                        p.showTitle(Title.title(
                                Component.text("FIGHT!", NamedTextColor.GREEN, TextDecoration.BOLD),
                                Component.text("Good luck!", NamedTextColor.GRAY),
                                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
                        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void endDuel(Duel duel, Component message) {
        if (duel.countdown != null) {
            duel.countdown.cancel();
        }
        duels.remove(duel.a);
        duels.remove(duel.b);
        for (UUID id : new UUID[]{duel.a, duel.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        Duel duel = duels.get(attacker.getUniqueId());
        if (duel != null && duel.active && duel.has(victim.getUniqueId())) {
            return; // agreed duel in progress - allow damage
        }

        event.setCancelled(true);
        attacker.sendActionBar(Component.text(
                "PvP is off! Challenge them with /pvp " + victim.getName(), NamedTextColor.RED));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Duel duel = duels.get(dead.getUniqueId());
        if (duel == null) {
            return;
        }
        Player winner = Bukkit.getPlayer(duel.other(dead.getUniqueId()));
        if (duel.active && winner != null) {
            endDuel(duel, Component.text()
                    .append(prefix())
                    .append(Component.text(winner.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" won the duel against ", NamedTextColor.GRAY))
                    .append(Component.text(dead.getName(), NamedTextColor.YELLOW))
                    .append(Component.text("!", NamedTextColor.GRAY))
                    .build());
        } else {
            endDuel(duel, Component.text()
                    .append(prefix())
                    .append(Component.text("The duel was cancelled.", NamedTextColor.GRAY))
                    .build());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        invites.remove(id);
        for (Map<UUID, Long> map : invites.values()) {
            map.remove(id);
        }

        Duel duel = duels.get(id);
        if (duel != null) {
            endDuel(duel, Component.text()
                    .append(prefix())
                    .append(Component.text(event.getPlayer().getName() + " left the game. Duel ended.",
                            NamedTextColor.GRAY))
                    .build());
        }
    }

    /** Works out which player caused the damage (direct hit, arrow, trident, snowball, etc.). */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return Collections.emptyList();
        }
        String typed = args[0].toLowerCase();
        List<String> out = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("pvp")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(player) && p.getName().toLowerCase().startsWith(typed)) {
                    out.add(p.getName());
                }
            }
        } else {
            Map<UUID, Long> pending = invites.get(player.getUniqueId());
            if (pending != null) {
                for (UUID id : pending.keySet()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.getName().toLowerCase().startsWith(typed)) {
                        out.add(p.getName());
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Component prefix() {
        return Component.text("[PvP] ", NamedTextColor.GOLD, TextDecoration.BOLD);
    }

    private static void msg(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text()
                .append(prefix())
                .append(Component.text(text, color))
                .build());
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }
}
