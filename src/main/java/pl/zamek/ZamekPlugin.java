package pl.zamek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ZamekPlugin extends JavaPlugin implements Listener {

    // ====== USTAWIENIA - zmien tutaj, jesli maja byc inne ======
    // Zamek
    private static final int INNER_W = 7;                          // szerokosc/dlugosc w srodku
    private static final int INNER_H = 5;                          // wysokosc w srodku
    private static final Material WALL = Material.BLACKSTONE;      // z czego jest zamek
    private static final long CASTLE_LIFETIME_TICKS = 10 * 20L;    // zamek znika po 10 sekundach
    private static final long CD_ZAMEK = 3 * 60 * 1000L;           // cooldown zamku: 3 min
    // Paleczka lewitki (emerald)
    private static final int LEVITATION_TICKS = 5 * 20;            // lewitacja 5 sekund
    private static final long CD_PALECZKA = 150 * 1000L;           // cooldown: 2:30 min
    // Lampion
    private static final int REGEN_TICKS = 4 * 20;                 // regeneracja 4 sekundy
    private static final int REGEN_LEVEL = 5;                      // regeneracja V
    private static final long CD_LAMPION = 2 * 60 * 1000L;         // cooldown: 2 min
    // Pole smierci (diament)
    private static final long FIELD_TICKS = 6 * 20L;               // blokada elytry 6 sekund
    private static final double FIELD_RADIUS = 2.5;                // kolo o srednicy 5
    private static final double FIELD_HEIGHT = 5;                  // zasieg w gore i w dol
    private static final long CD_POLE = 3 * 60 * 1000L;            // cooldown: 3 min
    // Usuwacz pajeczyn (blaze rod)
    private static final long CD_USUWACZ = 20 * 1000L;             // cooldown: 20 s
    // Usuwanie blokow
    private static final int CLEANUP_INTERVAL_SECONDS = 15 * 60;   // co 15 minut
    // ============================================================

    private static final int OUTER_W = INNER_W + 2;                // razem ze scianami
    private static final int OUTER_H = INNER_H + 2;                // razem z podloga i sufitem
    private static final Set<Integer> WARNINGS = Set.of(30, 15, 3, 2, 1);

    private static final String ID_ZAMEK = "zamek";
    private static final String ID_PALECZKA = "paleczka";
    private static final String ID_LAMPION = "lampion";
    private static final String ID_POLE = "pole";
    private static final String ID_USUWACZ = "usuwacz";

    private NamespacedKey key;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final List<Castle> castles = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final Set<String> placed = new HashSet<>();
    private BukkitTask cleanupTask;
    private int cleanupLeft;
    private int tickCount;

    // ---------------------------------------------------------------
    // klasy pomocnicze
    // ---------------------------------------------------------------

    /** Postawiony zamek: zapamietuje, co bylo wczesniej, zeby to przywrocic. */
    private static final class Castle {
        final World world;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final List<Block> blocks = new ArrayList<>();
        final List<BlockData> originals = new ArrayList<>();

        Castle(World world, int minX, int minY, int minZ) {
            this.world = world;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = minX + OUTER_W - 1;
            this.maxY = minY + OUTER_H - 1;
            this.maxZ = minZ + OUTER_W - 1;
        }

        boolean overlaps(Castle o) {
            return world.equals(o.world)
                    && minX <= o.maxX && maxX >= o.minX
                    && minY <= o.maxY && maxY >= o.minY
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }

        boolean isShell(Block b) {
            if (!b.getWorld().equals(world)) return false;
            int x = b.getX(), y = b.getY(), z = b.getZ();
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return false;
            return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
        }

        BoundingBox outerBox() {
            return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }

        BoundingBox innerBox() {
            return new BoundingBox(minX + 1, minY + 1, minZ + 1, maxX, maxY, maxZ);
        }

        Location center() {
            return new Location(world, minX + OUTER_W / 2 + 0.5, minY + 1, minZ + OUTER_W / 2 + 0.5);
        }
    }

    /** Pole smierci: kolo, w ktorym nie da sie leciec elytra. */
    private static final class Field {
        final World world;
        final double x, y, z;

        Field(World world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean contains(Player p) {
            Location l = p.getLocation();
            if (!l.getWorld().equals(world)) return false;
            double dx = l.getX() - x, dz = l.getZ() - z;
            return dx * dx + dz * dz <= FIELD_RADIUS * FIELD_RADIUS
                    && Math.abs(l.getY() - y) <= FIELD_HEIGHT;
        }

        void ring() {
            for (int i = 0; i < 24; i++) {
                double a = 2 * Math.PI * i / 24;
                world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                        x + Math.cos(a) * FIELD_RADIUS, y + 0.1, z + Math.sin(a) * FIELD_RADIUS,
                        1, 0, 0, 0, 0);
            }
        }
    }

    // ---------------------------------------------------------------
    // wlaczanie / wylaczanie
    // ---------------------------------------------------------------

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "przedmiot");
        getServer().getPluginManager().registerEvents(this, this);

        File f = new File(getDataFolder(), "bloki.yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            placed.addAll(cfg.getStringList("bloki"));
            if (cfg.getBoolean("wlaczone")) startCleanup();
        }

        getServer().getScheduler().runTaskTimer(this, this::tick, 2L, 2L);
        getServer().getScheduler().runTaskTimer(this, this::saveData, 20L * 120, 20L * 120);
    }

    @Override
    public void onDisable() {
        saveData();
        for (Castle c : new ArrayList<>(castles)) {
            remove(c);
        }
    }

    // ---------------------------------------------------------------
    // komendy
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("usuwanieblokow")) {
            toggleCleanup(sender);
            return true;
        }

        String id = switch (name) {
            case "zamek" -> ID_ZAMEK;
            case "paleczka" -> ID_PALECZKA;
            case "lampion" -> ID_LAMPION;
            case "polesmierci" -> ID_POLE;
            case "usuwaczpajeczyn" -> ID_USUWACZ;
            default -> null;
        };
        if (id == null) return false;

        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie ma takiego gracza online.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Użycie: /" + name + " <gracz>", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createItem(id)).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Dano przedmiot graczowi " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    // ---------------------------------------------------------------
    // przedmioty
    // ---------------------------------------------------------------

    private ItemStack createItem(String id) {
        ItemStack item = switch (id) {
            case ID_ZAMEK -> make(Material.DEEPSLATE_BRICKS, "Zamek", NamedTextColor.DARK_GRAY,
                    "Kliknij prawym, aby postawić zamek wokół siebie.",
                    "Uderz przeciwnika, aby postawić zamek na nim.");
            case ID_PALECZKA -> make(Material.EMERALD, "Pałeczka lewitki", NamedTextColor.GREEN,
                    "Uderz przeciwnika, aby oboje unieść w powietrze.");
            case ID_LAMPION -> make(Material.LANTERN, "Lampion", NamedTextColor.YELLOW,
                    "Kliknij prawym, aby dostać regenerację.");
            case ID_POLE -> make(Material.DIAMOND, "Pole śmierci", NamedTextColor.RED,
                    "Kliknij prawym stojąc na ziemi, aby zablokować elytrę.");
            case ID_USUWACZ -> make(Material.BLAZE_ROD, "Usuwacz pajęczyn", NamedTextColor.GOLD,
                    "Kliknij prawym, aby usunąć pajęczyny wokół siebie.");
            default -> throw new IllegalArgumentException(id);
        };
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack make(Material mat, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private String itemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------
    // cooldowny
    // ---------------------------------------------------------------

    private boolean onCooldown(Player p, String id, long ms) {
        Long last = cooldowns.get(p.getUniqueId() + ":" + id);
        long now = System.currentTimeMillis();
        if (last != null && now - last < ms) {
            long left = (ms - (now - last) + 999) / 1000;
            p.sendMessage(Component.text("Odczekaj jeszcze " + (left / 60) + "m " + (left % 60) + "s.", NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void startCooldown(Player p, String id) {
        cooldowns.put(p.getUniqueId() + ":" + id, System.currentTimeMillis());
    }

    private boolean canUse(Player p) {
        if (p.hasPermission("zamek.use")) return true;
        p.sendMessage(Component.text("Nie masz uprawnień.", NamedTextColor.RED));
        return false;
    }

    // ---------------------------------------------------------------
    // zdarzenia: uzycie przedmiotow
    // ---------------------------------------------------------------

    /** Prawy przycisk w powietrze albo w blok. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        String id = itemId(e.getItem());
        if (id == null || id.equals(ID_PALECZKA)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!canUse(p)) return;
        rightClick(p, id, null);
    }

    /** Prawy przycisk na zywa istote. */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        String id = itemId(p.getInventory().getItemInMainHand());
        if (id == null || id.equals(ID_PALECZKA)) return;

        e.setCancelled(true);
        if (!canUse(p)) return;
        LivingEntity target = e.getRightClicked() instanceof LivingEntity le ? le : null;
        rightClick(p, id, target);
    }

    /** Uderzenie istoty przedmiotem. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        String id = itemId(p.getInventory().getItemInMainHand());
        if (id == null) return;

        if (id.equals(ID_ZAMEK)) {
            e.setCancelled(true);
            if (!canUse(p)) return;
            useCastle(p, target);
        } else if (id.equals(ID_PALECZKA)) {
            if (!canUse(p)) return;
            useLevitation(p, target);
        }
    }

    private void rightClick(Player p, String id, LivingEntity target) {
        switch (id) {
            case ID_ZAMEK -> useCastle(p, target != null ? target : p);
            case ID_LAMPION -> useLantern(p);
            case ID_POLE -> useField(p);
            case ID_USUWACZ -> useWebRemover(p);
            default -> { }
        }
    }

    // ---------------------------------------------------------------
    // 1. zamek
    // ---------------------------------------------------------------

    private void useCastle(Player user, LivingEntity target) {
        if (onCooldown(user, ID_ZAMEK, CD_ZAMEK)) return;

        World w = target.getWorld();
        Location loc = target.getLocation();
        int half = OUTER_W / 2;
        int minX = loc.getBlockX() - half;
        int minZ = loc.getBlockZ() - half;
        int minY = loc.getBlockY() - 1;

        if (minY < w.getMinHeight() || minY + OUTER_H > w.getMaxHeight()) {
            user.sendMessage(Component.text("Tutaj zamek się nie zmieści (za nisko albo za wysoko).", NamedTextColor.RED));
            return;
        }

        Castle castle = new Castle(w, minX, minY, minZ);
        for (Castle other : castles) {
            if (castle.overlaps(other)) {
                user.sendMessage(Component.text("Tu już stoi inny zamek.", NamedTextColor.RED));
                return;
            }
        }
        if (!build(castle)) {
            user.sendMessage(Component.text("W miejscu zamku jest skrzynia lub inny pojemnik, nie można postawić.", NamedTextColor.RED));
            return;
        }

        castles.add(castle);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (castles.contains(castle)) remove(castle);
        }, CASTLE_LIFETIME_TICKS);

        // teleport na srodek
        Location center = castle.center();
        if (target.equals(user)) {
            teleportKeepLook(user, center);
        } else {
            teleportKeepLook(user, center.clone().add(-1, 0, 0));
            teleportKeepLook(target, center.clone().add(1, 0, 0));
        }

        startCooldown(user, ID_ZAMEK);
        user.sendMessage(Component.text("Postawiłeś zamek! Zniknie za 10 sekund.", NamedTextColor.GRAY));
    }

    private void teleportKeepLook(Entity e, Location to) {
        Location l = to.clone();
        l.setYaw(e.getLocation().getYaw());
        l.setPitch(e.getLocation().getPitch());
        e.teleport(l);
    }

    /** Stawia zamek. Zwraca false (i nic nie zmienia), jesli w srodku jest skrzynia/pojemnik. */
    private boolean build(Castle c) {
        World w = c.world;

        for (int x = c.minX; x <= c.maxX; x++) {
            for (int y = c.minY; y <= c.maxY; y++) {
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    if (w.getBlockAt(x, y, z).getState() instanceof Container) return false;
                }
            }
        }

        for (int x = c.minX; x <= c.maxX; x++) {
            for (int y = c.minY; y <= c.maxY; y++) {
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    c.blocks.add(b);
                    c.originals.add(b.getBlockData());
                    boolean shell = x == c.minX || x == c.maxX
                            || y == c.minY || y == c.maxY
                            || z == c.minZ || z == c.maxZ;
                    b.setType(shell ? WALL : Material.AIR, false);
                }
            }
        }
        return true;
    }

    /** Usuwa zamek. Bloki postawione przez graczy w srodku zostaja (poza banerami). */
    private void remove(Castle c) {
        castles.remove(c);
        for (int i = 0; i < c.blocks.size(); i++) {
            Block b = c.blocks.get(i);
            if (!c.isShell(b)) {
                Material now = b.getType();
                if (now != Material.AIR && !Tag.BANNERS.isTagged(now)) continue;
            }
            b.setBlockData(c.originals.get(i), false);
        }
    }

    private boolean isProtected(Block b) {
        for (Castle c : castles) {
            if (c.isShell(b)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isProtected(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    /** Istota zbugowana w scianie/podlodze zamku (czesciowo w srodku, czesciowo poza) wraca na srodek. */
    private void unstick(Castle c) {
        BoundingBox outer = c.outerBox();
        BoundingBox inner = c.innerBox();
        for (Entity en : c.world.getNearbyEntities(outer.clone().expand(1))) {
            if (!(en instanceof LivingEntity le) || le instanceof ArmorStand) continue;
            if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (le.isInsideVehicle()) continue;

            BoundingBox bb = le.getBoundingBox().clone().expand(-0.05);
            if (!outer.overlaps(bb) || inner.contains(bb)) continue;
            if (bb.getHeight() > inner.getHeight()
                    || bb.getWidthX() > inner.getWidthX()
                    || bb.getWidthZ() > inner.getWidthZ()) continue;

            teleportKeepLook(le, c.center());
        }
    }

    // ---------------------------------------------------------------
    // 2. Paleczka lewitki
    // ---------------------------------------------------------------

    private void useLevitation(Player p, LivingEntity target) {
        if (onCooldown(p, ID_PALECZKA, CD_PALECZKA)) return;
        PotionEffect effect = new PotionEffect(PotionEffectType.LEVITATION, LEVITATION_TICKS, 0);
        target.addPotionEffect(effect);
        p.addPotionEffect(effect);
        startCooldown(p, ID_PALECZKA);
        p.sendMessage(Component.text("Lewitacja na 5 sekund!", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------
    // 3. Lampion
    // ---------------------------------------------------------------

    private void useLantern(Player p) {
        if (onCooldown(p, ID_LAMPION, CD_LAMPION)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, REGEN_TICKS, REGEN_LEVEL - 1));
        startCooldown(p, ID_LAMPION);
        p.sendMessage(Component.text("Regeneracja na 4 sekundy!", NamedTextColor.YELLOW));
    }

    // ---------------------------------------------------------------
    // 4. Pole smierci
    // ---------------------------------------------------------------

    private boolean onGround(Player p) {
        if (p.isFlying() || p.isGliding()) return false;
        Location l = p.getLocation().clone().subtract(0, 0.2, 0);
        return !l.getBlock().isPassable();
    }

    private void useField(Player p) {
        if (onCooldown(p, ID_POLE, CD_POLE)) return;
        if (!onGround(p)) {
            p.sendMessage(Component.text("Musisz stać na ziemi.", NamedTextColor.RED));
            return;
        }
        Location l = p.getLocation();
        Field f = new Field(l.getWorld(), l.getX(), l.getY(), l.getZ());
        fields.add(f);
        getServer().getScheduler().runTaskLater(this, () -> fields.remove(f), FIELD_TICKS);
        startCooldown(p, ID_POLE);
        p.sendMessage(Component.text("Pole śmierci aktywne przez 6 sekund!", NamedTextColor.RED));
    }

    private boolean inAnyField(Player p) {
        for (Field f : fields) {
            if (f.contains(p)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding()) return;
        if (e.getEntity() instanceof Player p && inAnyField(p)) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------
    // 5. Usuwacz pajeczyn
    // ---------------------------------------------------------------

    private void useWebRemover(Player p) {
        if (onCooldown(p, ID_USUWACZ, CD_USUWACZ)) return;
        Block c = p.getLocation().getBlock();
        int removed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = c.getRelative(dx, dy, dz);
                    if (b.getType() == Material.COBWEB) {
                        b.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        startCooldown(p, ID_USUWACZ);
        p.sendMessage(Component.text("Usunięto pajęczyn: " + removed, NamedTextColor.GOLD));
    }

    // ---------------------------------------------------------------
    // 6. /usuwanieblokow
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material m = b.getType();
        if (Tag.WOOL.isTagged(m) || Tag.BANNERS.isTagged(m) || m == Material.COBWEB) {
            placed.add(b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ());
        }
    }

    private void toggleCleanup(CommandSender sender) {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
            sender.sendMessage(Component.text("Wyłączono usuwanie bloków.", NamedTextColor.RED));
        } else {
            startCleanup();
            sender.sendMessage(Component.text("Włączono usuwanie bloków co "
                    + (CLEANUP_INTERVAL_SECONDS / 60) + " minut.", NamedTextColor.GREEN));
        }
        saveData();
    }

    private void startCleanup() {
        cleanupLeft = CLEANUP_INTERVAL_SECONDS;
        cleanupTask = getServer().getScheduler().runTaskTimer(this, () -> {
            cleanupLeft--;
            if (cleanupLeft <= 0) {
                removePlaced();
                getServer().broadcast(Component.text("Bloki zostały usunięte.", NamedTextColor.BLUE));
                cleanupLeft = CLEANUP_INTERVAL_SECONDS;
            } else if (WARNINGS.contains(cleanupLeft)) {
                getServer().broadcast(Component.text("Do usunięcia bloków zostało " + cleanupLeft + "s.", NamedTextColor.BLUE));
            }
        }, 20L, 20L);
    }

    /** Usuwa wszystkie postawione przez graczy welny, banery i pajeczyny. */
    private void removePlaced() {
        for (String s : placed) {
            String[] t = s.split(";");
            if (t.length != 4) continue;
            World w = getServer().getWorld(t[0]);
            if (w == null) continue;
            int x, y, z;
            try {
                x = Integer.parseInt(t[1]);
                y = Integer.parseInt(t[2]);
                z = Integer.parseInt(t[3]);
            } catch (NumberFormatException ex) {
                continue;
            }
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (Tag.WOOL.isTagged(m) || Tag.BANNERS.isTagged(m) || m == Material.COBWEB) {
                b.setType(Material.AIR, false);
            }
        }
        placed.clear();
        saveData();
    }

    private void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("wlaczone", cleanupTask != null);
        cfg.set("bloki", new ArrayList<>(placed));
        File f = new File(getDataFolder(), "bloki.yml");
        f.getParentFile().mkdirs();
        try {
            cfg.save(f);
        } catch (IOException ex) {
            getLogger().warning("Nie udalo sie zapisac bloki.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // cykliczne zadanie (co 2 ticki)
    // ---------------------------------------------------------------

    private void tick() {
        tickCount++;

        if (!fields.isEmpty()) {
            for (Player pl : getServer().getOnlinePlayers()) {
                if (pl.isGliding() && inAnyField(pl)) pl.setGliding(false);
            }
            if (tickCount % 5 == 0) {
                for (Field f : fields) f.ring();
            }
        }

        for (Castle c : castles) {
            unstick(c);
        }
    }
}
