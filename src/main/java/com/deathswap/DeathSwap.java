package com.deathswap;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class DeathSwap extends JavaPlugin implements Listener, TabExecutor {

    /** Font defined in the resource pack: assets/deathswap/font/komika.json */
    private static final Key FONT = Key.key("deathswap", "komika");
    private static final UUID PACK_ID = UUID.fromString("5d1c6c64-7f0e-4f4e-9a3e-0b7f2f6f1a11");

    private enum State { IDLE, PREP, RUNNING }

    private State state = State.IDLE;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private BukkitTask task;
    private int prepLeft;
    private int swapLeft;

    private File kitFile;
    private ItemStack[] kit;

    // ------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kitFile = new File(getDataFolder(), "kit.yml");
        loadKitFromDisk();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("deathswap");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------ text helpers

    private Component t(String text, TextColor color) {
        return Component.text(text, color).font(FONT);
    }

    private void msg(CommandSender to, String text, TextColor color) {
        to.sendMessage(t(text, color));
    }

    private void broadcast(String text, TextColor color) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(t(text, color));
    }

    private void showTitle(Player p, String main, TextColor mainColor, String sub, TextColor subColor,
                           int fadeInMs, int stayMs, int fadeOutMs) {
        p.showTitle(Title.title(
                t(main, mainColor),
                t(sub, subColor),
                Title.Times.times(Duration.ofMillis(fadeInMs), Duration.ofMillis(stayMs), Duration.ofMillis(fadeOutMs))));
    }

    private void titleAll(String main, TextColor mainColor, String sub, TextColor subColor,
                          int fadeInMs, int stayMs, int fadeOutMs) {
        for (Player p : Bukkit.getOnlinePlayers())
            showTitle(p, main, mainColor, sub, subColor, fadeInMs, stayMs, fadeOutMs);
    }

    private String fmt(int s) {
        if (s >= 60 && s % 60 == 0) {
            int m = s / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return s + (s == 1 ? " second" : " seconds");
    }

    private void ping(Player p, float pitch) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
    }

    // ------------------------------------------------------------ resource pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        sendPack(p);
        if (state == State.RUNNING && !alive.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> p.setGameMode(GameMode.SPECTATOR), 5L);
        }
    }

    private void sendPack(Player p) {
        if (!getConfig().getBoolean("resource-pack.enabled", true)) return;
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        byte[] hash = hexToBytes(getConfig().getString("resource-pack.sha1", ""));
        boolean force = getConfig().getBoolean("resource-pack.force", false);
        p.setResourcePack(PACK_ID, url, hash, t("Download for the Death Swap font", NamedTextColor.GOLD), force);
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() != 40) return null;
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // ------------------------------------------------------------ kit

    private void loadKitFromDisk() {
        kit = null;
        if (!kitFile.exists()) return;
        List<?> list = YamlConfiguration.loadConfiguration(kitFile).getList("items");
        if (list == null) return;
        kit = new ItemStack[list.size()];
        for (int i = 0; i < kit.length; i++)
            kit[i] = list.get(i) instanceof ItemStack is ? is : null;
    }

    private void saveKitToDisk() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("items", kit == null ? null : Arrays.asList(kit));
        try {
            getDataFolder().mkdirs();
            yml.save(kitFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save kit: " + ex.getMessage());
        }
    }

    private void giveKit(Player p) {
        p.getInventory().clear();
        if (kit != null) p.getInventory().setContents(kit.clone());
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "/ds start | stop | kit <save|load|clear>", NamedTextColor.YELLOW);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> startPrep(sender);
            case "stop" -> {
                if (state == State.IDLE) { msg(sender, "No game is running.", NamedTextColor.RED); return true; }
                resetGame(true);
                broadcast("Death Swap was stopped.", NamedTextColor.RED);
            }
            case "kit" -> {
                if (!(sender instanceof Player p)) { msg(sender, "Players only.", NamedTextColor.RED); return true; }
                if (args.length < 2) { msg(p, "/ds kit <save|load|clear>", NamedTextColor.YELLOW); return true; }
                switch (args[1].toLowerCase()) {
                    case "save" -> {
                        kit = p.getInventory().getContents().clone();
                        saveKitToDisk();
                        msg(p, "Kit saved from your current inventory.", NamedTextColor.GREEN);
                    }
                    case "load" -> {
                        if (kit == null) { msg(p, "No kit saved yet.", NamedTextColor.RED); return true; }
                        giveKit(p);
                        msg(p, "Kit loaded into your inventory.", NamedTextColor.GREEN);
                    }
                    case "clear" -> {
                        kit = null;
                        saveKitToDisk();
                        msg(p, "Kit cleared.", NamedTextColor.YELLOW);
                    }
                    default -> msg(p, "/ds kit <save|load|clear>", NamedTextColor.YELLOW);
                }
            }
            default -> msg(sender, "Unknown subcommand.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("start", "stop", "kit");
        if (args.length == 2 && args[0].equalsIgnoreCase("kit")) return List.of("save", "load", "clear");
        return List.of();
    }

    // ------------------------------------------------------------ game flow

    private void startPrep(CommandSender sender) {
        if (state != State.IDLE) { msg(sender, "A game is already running.", NamedTextColor.RED); return; }
        if (Bukkit.getOnlinePlayers().size() < 2) { msg(sender, "You need at least 2 players online.", NamedTextColor.RED); return; }

        state = State.PREP;
        prepLeft = Math.max(5, getConfig().getInt("prep-seconds", 300));

        titleAll("Starting in " + fmt(prepLeft), NamedTextColor.GOLD, "Death Swap", NamedTextColor.RED, 500, 3000, 1000);
        broadcast("Death Swap starts in " + fmt(prepLeft) + "!", NamedTextColor.GOLD);
        for (Player p : Bukkit.getOnlinePlayers()) ping(p, 1f);

        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void tick() {
        if (state == State.PREP) {
            prepLeft--;
            if (prepLeft <= 0) { beginGame(); return; }
            if (prepLeft == 60 || prepLeft == 30 || prepLeft == 10 || prepLeft <= 5) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendActionBar(t("Starting in " + fmt(prepLeft), NamedTextColor.GOLD));
                    if (prepLeft <= 5) ping(p, 1f);
                }
            }
        } else if (state == State.RUNNING) {
            swapLeft--;

            if (swapLeft == 10) {
                forAlive(p -> {
                    showTitle(p, "Teleporting in 10 seconds", NamedTextColor.RED, "Get ready!", NamedTextColor.YELLOW, 300, 2500, 500);
                    ping(p, 1f);
                });
            } else if (swapLeft < 10 && swapLeft > 0) {
                forAlive(p -> {
                    if (swapLeft <= 5)
                        showTitle(p, String.valueOf(swapLeft), NamedTextColor.RED, "Teleporting...", NamedTextColor.YELLOW, 0, 800, 100);
                    else
                        p.sendActionBar(t("Teleporting in " + swapLeft, NamedTextColor.RED));
                    ping(p, 1.5f);
                });
            }

            // 5 seconds before the swap: switch to survival and load the kit
            if (swapLeft == 5) {
                forAlive(p -> {
                    p.setGameMode(GameMode.SURVIVAL);
                    giveKit(p);
                    p.sendMessage(t("Kit loaded! Survival mode on.", NamedTextColor.GREEN));
                });
            }

            if (swapLeft <= 0) {
                doSwap();
                swapLeft = getConfig().getInt("swap-seconds", 300);
            }
        }
    }

    private void forAlive(java.util.function.Consumer<Player> action) {
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) action.accept(p);
        }
    }

    private void beginGame() {
        alive.clear();
        deathLocations.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            alive.add(p.getUniqueId());
            p.setGameMode(GameMode.SURVIVAL);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            giveKit(p);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
        state = State.RUNNING;
        swapLeft = getConfig().getInt("swap-seconds", 300);
        titleAll("Death Swap begins!", NamedTextColor.RED, "Swap every " + fmt(swapLeft), NamedTextColor.YELLOW, 300, 3000, 700);
        broadcast("Good luck! Last player standing wins.", NamedTextColor.GOLD);
    }

    private void doSwap() {
        List<Player> order = new ArrayList<>();
        forAlive(order::add);
        if (order.size() < 2) return;
        Collections.shuffle(order);

        List<Location> spots = new ArrayList<>();
        for (Player p : order) spots.add(p.getLocation().clone());

        // everyone moves to the next player's spot (a full rotation, so with 2 players it's a plain swap)
        for (int i = 0; i < order.size(); i++) {
            order.get(i).teleport(spots.get((i + 1) % order.size()));
            ping(order.get(i), 0.6f);
        }
        broadcast("SWAP!", NamedTextColor.LIGHT_PURPLE);
    }

    private void checkWin() {
        if (state != State.RUNNING || alive.size() > 1) return;

        Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());
        if (task != null) task.cancel();
        task = null;
        state = State.IDLE;

        if (winner != null) {
            titleAll(winner.getName() + " WINS!", NamedTextColor.GOLD, "Last player standing", NamedTextColor.YELLOW, 500, 6000, 1000);
            broadcast(winner.getName() + " won Death Swap!", NamedTextColor.GOLD);
        } else {
            titleAll("Nobody wins!", NamedTextColor.RED, "Everyone died", NamedTextColor.YELLOW, 500, 5000, 1000);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (state == State.IDLE)
                for (Player p : Bukkit.getOnlinePlayers()) p.setGameMode(GameMode.SURVIVAL);
        }, 20L * 15);
        alive.clear();
    }

    private void resetGame(boolean restoreModes) {
        if (task != null) task.cancel();
        task = null;
        state = State.IDLE;
        alive.clear();
        deathLocations.clear();
        if (restoreModes) for (Player p : Bukkit.getOnlinePlayers()) p.setGameMode(GameMode.SURVIVAL);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (state != State.RUNNING || !alive.remove(p.getUniqueId())) return;
        deathLocations.put(p.getUniqueId(), p.getLocation());
        broadcast(p.getName() + " was eliminated! " + alive.size() + " left.", NamedTextColor.RED);
        Bukkit.getScheduler().runTask(this, this::checkWin);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Location loc = deathLocations.get(p.getUniqueId());
        if (loc != null && state == State.RUNNING) {
            e.setRespawnLocation(loc);
            Bukkit.getScheduler().runTaskLater(this, () -> p.setGameMode(GameMode.SPECTATOR), 2L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (state == State.RUNNING && alive.remove(e.getPlayer().getUniqueId())) {
            broadcast(e.getPlayer().getName() + " left the game. " + alive.size() + " left.", NamedTextColor.RED);
            Bukkit.getScheduler().runTask(this, this::checkWin);
        }
    }
}
