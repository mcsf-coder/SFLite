package org.simpleveinpickx.simpleVeinPickX;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class SimpleVeinPickX extends JavaPlugin implements Listener {

    private NamespacedKey pickaxeKey;
    private NamespacedKey crafterKey;
    private NamespacedKey chestKey;
    private NamespacedKey itemChestDataKey;
    private NamespacedKey heroBootsKey;
    private NamespacedKey frogHelmetKey;
    private NamespacedKey yonduKey;
    private NamespacedKey portalGunKey;
    private NamespacedKey guideKey;

    private final Set processingBlocks = new HashSet();
    private final Map openPlacedChests = new HashMap();
    private final Map openHandheldSlots = new HashMap();
    private final Map lastChestClickTime = new HashMap();
    private final Set frogWearers = new HashSet();

    private final Map yonduChargeStart = new HashMap();
    private final Map yonduLastPing = new HashMap();
    private final Map yonduFlightTicks = new HashMap();

    private final Map bluePortals = new HashMap();
    private final Map orangePortals = new HashMap();
    private final Map portalCooldowns = new HashMap();

    private static final int MAX_VEIN_SIZE = 32;
    private static final int[] GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT_SLOT = 24;

    private static final String GUI_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "SF Crafting Table";
    private static final String GUIDE_CATEGORIES_TITLE = ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "SF Lite - Categories";
    private static final String RECIPE_VIEW_TITLE = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "SF Lite - Recipe";
    private static final String SF_CHEST_GUI_TITLE = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "SF Chest";

    @Override
    public void onEnable() {
        pickaxeKey = new NamespacedKey(this, "pickaxe_of_vein");
        crafterKey = new NamespacedKey(this, "sf_crafting_table");
        chestKey = new NamespacedKey(this, "sf_chest");
        itemChestDataKey = new NamespacedKey(this, "sf_chest_item_contents");
        heroBootsKey = new NamespacedKey(this, "super_hero_landing");
        frogHelmetKey = new NamespacedKey(this, "frog_helmet");
        yonduKey = new NamespacedKey(this, "yondu_yaka");
        portalGunKey = new NamespacedKey(this, "portal_gun");
        guideKey = new NamespacedKey(this, "sf_lite_guide");
        getServer().getPluginManager().registerEvents(this, this);
        registerCrafterRecipe();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                if (isFrogCap(player.getInventory().getHelmet())) {
                    frogWearers.add(uuid);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, true, false, true));
                } else if (frogWearers.remove(uuid)) {
                    player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                }
            }
        }, 10L, 20L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();

            Iterator chargeIt = yonduChargeStart.entrySet().iterator();
            while (chargeIt.hasNext()) {
                Map.Entry entry = (Map.Entry) chargeIt.next();
                UUID uuid = (UUID) entry.getKey();
                Player player = Bukkit.getPlayer(uuid);

                if (player == null || !player.isOnline() || !isYonduYaka(player.getInventory().getItemInMainHand())) {
                    chargeIt.remove();
                    yonduLastPing.remove(uuid);
                    continue;
                }

                long startMs = (Long) entry.getValue();
                long lastPingMs = (Long) yonduLastPing.getOrDefault(uuid, startMs);

                if ((now - lastPingMs) > 320L) {
                    long heldMs = Math.min(5000L, Math.max(200L, lastPingMs - startMs));
                    chargeIt.remove();
                    yonduLastPing.remove(uuid);
                    launchYonduYaka(player, heldMs);
                    continue;
                }

                long elapsed = Math.min(5000L, now - startMs);
                double seconds = elapsed / 1000.0;
                int filledBars = (int) Math.round((elapsed / 5000.0) * 10);
                StringBuilder bar = new StringBuilder(ChatColor.RED + "Yondu Yaka " + ChatColor.DARK_GRAY + "[");
                for (int i = 0; i < 10; i++) {
                    bar.append(i < filledBars ? ChatColor.GOLD + "■" : ChatColor.GRAY + "□");
                }
                bar.append(ChatColor.DARK_GRAY).append("] ").append(ChatColor.YELLOW).append(String.format("%.1fs", seconds));
                if (elapsed >= 5000L) bar.append(ChatColor.RED).append(ChatColor.BOLD).append(" MAX!");
                player.sendActionBar(bar.toString());

                float chargePitch = 0.8f + (float) ((elapsed / 5000.0) * 1.1f);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE, 0.35f, chargePitch);
            }

            Iterator flightIt = yonduFlightTicks.entrySet().iterator();
            while (flightIt.hasNext()) {
                Map.Entry entry = (Map.Entry) flightIt.next();
                UUID uuid = (UUID) entry.getKey();
                int ticksLeft = (Integer) entry.getValue();
                Player player = Bukkit.getPlayer(uuid);

                if (player == null || !player.isOnline() || ticksLeft == 0) {
                    flightIt.remove();
                    continue;
                }

                Particle.DustOptions redDust = new Particle.DustOptions(Color.RED, 1.4f);
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 6, 0.25, 0.25, 0.25, 0, redDust);

                for (Object obj : player.getNearbyEntities(1.8, 1.8, 1.8)) {
                    Entity entity = (Entity) obj;
                    if (entity instanceof LivingEntity target && !target.equals(player)) {
                        target.damage(10.0, player);
                    }
                }

                yonduFlightTicks.put(uuid, Math.max(0, ticksLeft - 2));
            }
        }, 2L, 2L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            Particle.DustOptions blueDust = new Particle.DustOptions(Color.AQUA, 1.5f);
            Particle.DustOptions orangeDust = new Particle.DustOptions(Color.ORANGE, 1.5f);

            for (Object obj : bluePortals.entrySet()) {
                Map.Entry entry = (Map.Entry) obj;
                UUID ownerId = (UUID) entry.getKey();
                org.bukkit.Location blueLoc = (org.bukkit.Location) entry.getValue();
                org.bukkit.Location orangeLoc = (org.bukkit.Location) orangePortals.get(ownerId);

                if (blueLoc == null || blueLoc.getWorld() == null) continue;
                spawnPortalRing(blueLoc, blueDust);

                if (orangeLoc != null && orangeLoc.getWorld() != null) {
                    for (Object entObj : blueLoc.getWorld().getNearbyEntities(blueLoc, 0.9, 1.2, 0.9)) {
                        Entity entity = (Entity) entObj;
                        if (entity instanceof LivingEntity living) {
                            Long cd = (Long) portalCooldowns.get(living.getUniqueId());
                            if (cd == null || (now - cd) > 1500L) {
                                portalCooldowns.put(living.getUniqueId(), now);
                                org.bukkit.Location dest = orangeLoc.clone();
                                dest.setYaw(living.getLocation().getYaw());
                                dest.setPitch(living.getLocation().getPitch());
                                living.teleport(dest);
                                dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.3f);
                            }
                        }
                    }
                }
            }
            for (Object obj : orangePortals.entrySet()) {
                Map.Entry entry = (Map.Entry) obj;
                UUID ownerId = (UUID) entry.getKey();
                org.bukkit.Location orangeLoc = (org.bukkit.Location) entry.getValue();
                org.bukkit.Location blueLoc = (org.bukkit.Location) bluePortals.get(ownerId);
                if (orangeLoc == null || orangeLoc.getWorld() == null) continue;
                spawnPortalRing(orangeLoc, orangeDust);
                if (blueLoc != null && blueLoc.getWorld() != null) {
                    for (Object entObj : orangeLoc.getWorld().getNearbyEntities(orangeLoc, 0.9, 1.2, 0.9)) {
                        Entity entity = (Entity) entObj;
                        if (entity instanceof LivingEntity living) {
                            Long cd = (Long) portalCooldowns.get(living.getUniqueId());
                            if (cd == null || (now - cd) > 1500L) {
                                portalCooldowns.put(living.getUniqueId(), now);
                                org.bukkit.Location dest = blueLoc.clone();
                                dest.setYaw(living.getLocation().getYaw());
                                dest.setPitch(living.getLocation().getPitch());
                                living.teleport(dest);
                                dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.3f);
                            }
                        }
                    }
                }
            }
        }, 4L, 4L);
    }


    private void launchYonduYaka(Player player, long chargeMs) {
        double ratio = Math.min(1.0, Math.max(0.15, chargeMs / 5000.0));
        double baseSpeed = 1.2 + (ratio * 2.7);

        Vector dir = player.getLocation().getDirection();
        if (dir.lengthSquared() == 0.0) {
            dir = new Vector(0, 0.5, 0);
        } else {
            dir.normalize();
        }

        double horizontalBoost = 1.35;
        double vx = Math.max(-5.2, Math.min(5.2, dir.getX() * baseSpeed * horizontalBoost));
        double vz = Math.max(-5.2, Math.min(5.2, dir.getZ() * baseSpeed * horizontalBoost));
        double vy = Math.max(-3.8, Math.min(3.8, Math.max(dir.getY() * baseSpeed, 0.35 + (ratio * 0.42))));

        if (Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)) {
            player.setVelocity(new Vector(vx, vy, vz));
        }
        player.setFallDistance(0f);

        int flightDuration = (int) (14 + (ratio * 46));
        yonduFlightTicks.put(player.getUniqueId(), flightDuration);

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 1.2f);
        playYonduWhistle(player);

        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (isYonduYaka(hand) && hand.getItemMeta() instanceof Damageable dmg) {
                int nextDamage = dmg.getDamage() + 1;
                if (nextDamage == hand.getType().getMaxDurability()) {
                    player.getInventory().setItemInMainHand(null);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                } else {
                    dmg.setDamage(nextDamage);
                    hand.setItemMeta(dmg);
                }
            }
        }

        player.sendActionBar(ChatColor.RED + "" + ChatColor.BOLD + "YAKA LAUNCH! " + ChatColor.YELLOW + "(" + String.format("%.1fs", chargeMs / 1000.0) + " charge)");
    }

    private void playYonduWhistle(Player player) {
        float[] notes = {1.414f, 1.681f, 1.887f, 1.681f, 1.414f};
        long[] delays = {0L, 2L, 4L, 7L, 9L};
        for (int i = 0; i < notes.length; i++) {
            final float pitch = notes[i];
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.2f, pitch);
                }
            }, delays[i]);
        }
    }

    private void registerCrafterRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "sf_crafting_table_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createSFCraftingTable());
        recipe.shape("PPP", "PPP", "PPP");
        recipe.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        Bukkit.addRecipe(recipe);
    }

    public ItemStack createSFLiteGuide() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "SF Lite" + ChatColor.GRAY + " (Guide)");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "Right-Click" + ChatColor.GRAY + " to open categories",
                    ChatColor.GRAY + "and view crafting recipes."
            ));
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(guideKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        return book;
    }

    public ItemStack createSFCraftingTable() {
        ItemStack table = new ItemStack(Material.TARGET);
        ItemMeta meta = table.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "SF Crafting Table");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "Right-Click in hand" + ChatColor.GRAY + " or place on ground",
                    ChatColor.GRAY + "to craft special SF Lite items.",
                    ChatColor.DARK_GRAY + "SF Lite Workstation"
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.getPersistentDataContainer().set(crafterKey, PersistentDataType.BYTE, (byte) 1);
            table.setItemMeta(meta);
        }
        return table;
    }

    public ItemStack createVeinPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.WOODEN_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Pickaxe of Vein");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Mines entire veins of ore at once.",
                    ChatColor.AQUA + "Diamond-tier mining power.",
                    ChatColor.DARK_GRAY + "SF Lite Tool"
            ));
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);

            org.bukkit.inventory.meta.components.ToolComponent tool = meta.getTool();
            tool.setDefaultMiningSpeed(1.0f);
            tool.addRule(Tag.MINEABLE_PICKAXE, 8.0f, true);
            meta.setTool(tool);

            meta.getPersistentDataContainer().set(pickaxeKey, PersistentDataType.BYTE, (byte) 1);
            pickaxe.setItemMeta(meta);
        }
        return pickaxe;
    }

    public ItemStack createSFChest() {
        ItemStack chest = new ItemStack(Material.CYAN_SHULKER_BOX);
        ItemMeta meta = chest.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "SF Chest");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "54-slot storage (Double Chest size).",
                    ChatColor.YELLOW + "Right-Click in hand" + ChatColor.GRAY + " or place on ground.",
                    ChatColor.GRAY + "Double-click any slot inside to auto-sort.",
                    ChatColor.DARK_GRAY + "SF Lite Storage"
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.getPersistentDataContainer().set(chestKey, PersistentDataType.BYTE, (byte) 1);
            chest.setItemMeta(meta);
        }
        return chest;
    }

    public ItemStack createHeroBoots() {
        ItemStack boots = new ItemStack(Material.CHAINMAIL_BOOTS);
        ItemMeta meta = boots.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Super Hero Landing");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Transfers all fall damage to nearby",
                    ChatColor.GRAY + "mobs and players (bypasses PvP).",
                    ChatColor.YELLOW + "Single-use: breaks upon impact!",
                    ChatColor.DARK_GRAY + "SF Lite Armor"
            ));
            meta.addEnchant(Enchantment.FEATHER_FALLING, 1, true);
            meta.getPersistentDataContainer().set(heroBootsKey, PersistentDataType.BYTE, (byte) 1);
            boots.setItemMeta(meta);
        }
        return boots;
    }

    public ItemStack createFrogCap() {
        ItemStack cap = new ItemStack(Material.LEATHER_HELMET);
        LeatherArmorMeta meta = (LeatherArmorMeta) cap.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Frog");
            meta.setColor(Color.GREEN);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Grants " + ChatColor.AQUA + "+Night Vision" + ChatColor.GRAY + " while worn.",
                    ChatColor.DARK_GRAY + "SF Lite Armor"
            ));
            meta.addEnchant(Enchantment.RESPIRATION, 3, true);
            meta.getPersistentDataContainer().set(frogHelmetKey, PersistentDataType.BYTE, (byte) 1);
            cap.setItemMeta(meta);
        }
        return cap;
    }

    public ItemStack createYonduYaka() {
        ItemStack spear = new ItemStack(Material.COPPER_SPEAR);
        ItemMeta meta = spear.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Yondu Yaka");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Hold " + ChatColor.YELLOW + "Right-Click" + ChatColor.GRAY + " (up to 5s) to charge",
                    ChatColor.GRAY + "and whistle-launch with " + ChatColor.AQUA + "Riptide X" + ChatColor.GRAY + " power.",
                    ChatColor.DARK_GRAY + "SF Lite Weapon"
            ));
            meta.addEnchant(Enchantment.RIPTIDE, 10, true);
            meta.getPersistentDataContainer().set(yonduKey, PersistentDataType.BYTE, (byte) 1);
            spear.setItemMeta(meta);
        }
        return spear;
    }

    public ItemStack createPortalGun() {
        ItemStack gun = new ItemStack(Material.BREEZE_ROD);
        ItemMeta meta = gun.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Portal Gun");
            meta.setLore(Arrays.asList(
                    ChatColor.AQUA + "Left-Click" + ChatColor.GRAY + " to shoot " + ChatColor.AQUA + "Blue Portal",
                    ChatColor.GOLD + "Right-Click" + ChatColor.GRAY + " to shoot " + ChatColor.GOLD + "Orange Portal",
                    ChatColor.YELLOW + "Shift + Left-Click" + ChatColor.GRAY + " to clear portals",
                    ChatColor.DARK_GRAY + "SF Lite Gadget"
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 10, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(portalGunKey, PersistentDataType.BYTE, (byte) 1);
            gun.setItemMeta(meta);
        }
        return gun;
    }

    private void shootPortal(Player player, boolean isBlue) {
        org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(50.0, org.bukkit.FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            player.sendActionBar(ChatColor.RED + "Out of range (max 50 blocks)!");
            return;
        }
        Block hitBlock = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        org.bukkit.Location portalLoc = hitBlock.getRelative(face).getLocation().add(0.5, 0.1, 0.5);
        org.bukkit.Location start = player.getEyeLocation();
        Vector step = portalLoc.toVector().subtract(start.toVector());
        double dist = step.length();
        if (dist != 0.0) {
            Vector unit = step.normalize().multiply(0.8);
            Particle.DustOptions beamDust = new Particle.DustOptions(isBlue ? Color.AQUA : Color.ORANGE, 1.0f);
            org.bukkit.Location cursor = start.clone();
            int steps = (int) (dist / 0.8);
            for (int i = 0; i != steps; i++) {
                cursor.add(unit);
                start.getWorld().spawnParticle(Particle.DUST, cursor, 1, 0, 0, 0, 0, beamDust);
            }
        }
        if (isBlue) {
            bluePortals.put(player.getUniqueId(), portalLoc);
            player.sendActionBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Blue Portal" + ChatColor.GRAY + " placed!");
        } else {
            orangePortals.put(player.getUniqueId(), portalLoc);
            player.sendActionBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Orange Portal" + ChatColor.GRAY + " placed!");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, isBlue ? 1.4f : 1.0f);
    }
    private void spawnPortalRing(org.bukkit.Location center, Particle.DustOptions dust) {
        for (int i = 0; i != 12; i++) {
            double angle = (Math.PI * 2 * i) / 12.0;
            double x = Math.cos(angle) * 0.55;
            double z = Math.sin(angle) * 0.55;
            double y = (i % 2 == 0) ? 0.2 : 1.3;
            center.getWorld().spawnParticle(Particle.DUST, center.clone().add(x, y, z), 1, 0, 0, 0, 0, dust);
        }
    }
    private void clearPortals(Player player) {
        UUID uuid = player.getUniqueId();
        bluePortals.remove(uuid);
        orangePortals.remove(uuid);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.2f);
        player.sendMessage(ChatColor.YELLOW + "All active portals cleared!");
    }

    private boolean isPortalGun(ItemStack item) {
        if (item == null || item.getType() != Material.BREEZE_ROD || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(portalGunKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("Portal Gun"));
    }

    private boolean isYonduYaka(ItemStack item) {
        if (item == null || item.getType() != Material.COPPER_SPEAR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(yonduKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("Yondu Yaka"));
    }

    private boolean isFrogCap(ItemStack item) {
        if (item == null || item.getType() != Material.LEATHER_HELMET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(frogHelmetKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("Frog"));
    }

    private boolean isHeroBoots(ItemStack item) {
        if (item == null || item.getType() != Material.CHAINMAIL_BOOTS || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(heroBootsKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("Super Hero Landing"));
    }

    private boolean isSFCraftingTableItem(ItemStack item) {
        if (item == null || item.getType() != Material.TARGET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(crafterKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("SF Crafting Table"));
    }

    private boolean isSFCraftingTableBlock(Block block) {
        if (block == null || block.getType() != Material.TARGET) return false;
        NamespacedKey blockKey = new NamespacedKey(this, "sf_table_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
        return block.getChunk().getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE);
    }

    private boolean isSFChestItem(ItemStack item) {
        if (item == null || item.getType() != Material.CYAN_SHULKER_BOX || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(chestKey, PersistentDataType.BYTE)
                || (meta.hasDisplayName() && ChatColor.stripColor(meta.getDisplayName()).equals("SF Chest"));
    }

    private boolean isSFChestBlock(Block block) {
        if (block == null || block.getType() != Material.CYAN_SHULKER_BOX) return false;
        NamespacedKey blockKey = new NamespacedKey(this, "sf_chest_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
        return block.getChunk().getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NamespacedKey starterKey = new NamespacedKey(this, "received_sf_lite_guide");
        if (!player.getPersistentDataContainer().has(starterKey, PersistentDataType.BYTE)) {
            player.getInventory().addItem(createSFLiteGuide());
            player.getPersistentDataContainer().set(starterKey, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(ChatColor.GREEN + "Welcome! You received the " + ChatColor.BOLD + "SF Lite" + ChatColor.GREEN + " guide book! Right-click it or type " + ChatColor.YELLOW + "/sf" + ChatColor.GREEN + ".");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        frogWearers.remove(uuid);
        yonduChargeStart.remove(uuid);
        yonduLastPing.remove(uuid);
        yonduFlightTicks.remove(uuid);
        bluePortals.remove(uuid);
        orangePortals.remove(uuid);
        portalCooldowns.remove(uuid);
    }

    @EventHandler
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        yonduChargeStart.remove(uuid);
        yonduLastPing.remove(uuid);
    }

    @EventHandler
    public void onPlayerRiptide(PlayerRiptideEvent event) {
        if (isYonduYaka(event.getItem())) {
            event.getPlayer().setVelocity(new Vector(0, 0, 0));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("sf")) {
            player.getInventory().addItem(createSFLiteGuide());
            player.sendMessage(ChatColor.GREEN + "You received the " + ChatColor.BOLD + "SF Lite" + ChatColor.GREEN + " guide book!");
            return true;
        }
        if (command.getName().equalsIgnoreCase("giveall")) {
            player.getInventory().addItem(
                    createSFCraftingTable(),
                    createVeinPickaxe(),
                    createSFChest(),
                    createHeroBoots(),
                    createFrogCap(),
                    createYonduYaka(),
                    createPortalGun()
            );
            player.sendMessage(ChatColor.GREEN + "You received all " + ChatColor.BOLD + "SF Lite" + ChatColor.GREEN + " items!");
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (yonduFlightTicks.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        ItemStack boots = player.getInventory().getBoots();
        if (!isHeroBoots(boots)) return;

        double fallDamage = event.getDamage();
        if (fallDamage == 0.0) return;

        event.setCancelled(true);
        player.getInventory().setBoots(null);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.9f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 3, 0.5, 0.2, 0.5, 0.05);

        double radius = 6.0;
        for (Object obj : player.getNearbyEntities(radius, radius, radius)) {
            Entity entity = (Entity) obj;
            if (entity instanceof LivingEntity target && !target.equals(player)) {
                double newHealth = Math.max(0.0, target.getHealth() - fallDamage);
                target.setHealth(newHealth);
                target.playHurtAnimation(0);
            }
        }

        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "BOOM! " + ChatColor.GRAY + "Super Hero Landing transferred " + ChatColor.GOLD + String.format("%.1f", fallDamage) + ChatColor.GRAY + " damage!");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();

        if (isSFCraftingTableItem(item)) {
            NamespacedKey blockKey = new NamespacedKey(this, "sf_table_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
            block.getChunk().getPersistentDataContainer().set(blockKey, PersistentDataType.BYTE, (byte) 1);
            event.getPlayer().sendMessage(ChatColor.LIGHT_PURPLE + "SF Crafting Table placed!");
        } else if (isSFChestItem(item)) {
            PersistentDataContainer chunkPdc = block.getChunk().getPersistentDataContainer();
            NamespacedKey blockKey = new NamespacedKey(this, "sf_chest_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
            chunkPdc.set(blockKey, PersistentDataType.BYTE, (byte) 1);

            byte[] savedBytes = item.getItemMeta().getPersistentDataContainer().get(itemChestDataKey, PersistentDataType.BYTE_ARRAY);
            if (savedBytes != null && savedBytes.length != 0) {
                NamespacedKey dataKey = new NamespacedKey(this, "sf_chest_data_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
                chunkPdc.set(dataKey, PersistentDataType.BYTE_ARRAY, savedBytes);
            }
            event.getPlayer().sendMessage(ChatColor.AQUA + "SF Chest (54 Slots) placed!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        ItemStack item = event.getItem();
        Player player = event.getPlayer();

        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) && item != null && item.hasItemMeta()) {
            if (item.getItemMeta().getPersistentDataContainer().has(guideKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openCategoriesMenu(player);
                return;
            }
        }

        if (isPortalGun(item)) {
            if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                if (player.isSneaking()) {
                    clearPortals(player);
                } else {
                    shootPortal(player, true);
                }
                return;
            }
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                boolean clickingCustomBlock = false;
                if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                    Block clicked = event.getClickedBlock();
                    if (isSFCraftingTableBlock(clicked) || isSFChestBlock(clicked)) {
                        clickingCustomBlock = true;
                    }
                }
                if (!clickingCustomBlock) {
                    event.setCancelled(true);
                    shootPortal(player, false);
                    return;
                }
            }
        }
        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) && isYonduYaka(item)) {
            boolean clickingCustomBlock = false;
            if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                Block clicked = event.getClickedBlock();
                if (isSFCraftingTableBlock(clicked) || isSFChestBlock(clicked)) {
                    clickingCustomBlock = true;
                }
            }
            if (!clickingCustomBlock) {
                event.setCancelled(true);
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                yonduChargeStart.putIfAbsent(uuid, now);
                yonduLastPing.put(uuid, now);
                return;
            }
        }
        if (isSFCraftingTableItem(item)) {
            if (action == Action.RIGHT_CLICK_AIR || (action == Action.RIGHT_CLICK_BLOCK && player.isSneaking())) {
                event.setCancelled(true);
                openSFCraftingGUI(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return;
            }
        }
        if (isSFChestItem(item)) {
            if (action == Action.RIGHT_CLICK_AIR || (action == Action.RIGHT_CLICK_BLOCK && player.isSneaking())) {
                event.setCancelled(true);
                int heldSlot = player.getInventory().getHeldItemSlot();
                Inventory inv = loadHandheldSFChestInventory(item);
                openHandheldSlots.put(player.getUniqueId(), heldSlot);
                openPlacedChests.remove(player.getUniqueId());
                player.openInventory(inv);
                player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 0.7f, 1.2f);
                return;
            }
        }
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block block = event.getClickedBlock();
            if (isSFCraftingTableBlock(block)) {
                event.setCancelled(true);
                openSFCraftingGUI(player);
                return;
            }
            if (isSFChestBlock(block)) {
                event.setCancelled(true);
                Inventory inv = loadPlacedSFChestInventory(block);
                openPlacedSFChestGUI(player, block, inv);
            }
        }
    }
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (openHandheldSlots.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (openHandheldSlots.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
    private void openPlacedSFChestGUI(Player player, Block block, Inventory loadedInv) {
        for (Object obj : openPlacedChests.entrySet()) {
            Map.Entry entry = (Map.Entry) obj;
            if (entry.getValue().equals(block)) {
                Player other = Bukkit.getPlayer((UUID) entry.getKey());
                if (other != null && other.getOpenInventory().getTitle().equals(SF_CHEST_GUI_TITLE)) {
                    openPlacedChests.put(player.getUniqueId(), block);
                    openHandheldSlots.remove(player.getUniqueId());
                    player.openInventory(other.getOpenInventory().getTopInventory());
                    player.playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 0.7f, 1.1f);
                    return;
                }
            }
        }
        openPlacedChests.put(player.getUniqueId(), block);
        openHandheldSlots.remove(player.getUniqueId());
        player.openInventory(loadedInv);
        player.playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 0.7f, 1.1f);
    }
    private Inventory loadHandheldSFChestInventory(ItemStack item) {
        Inventory inv = Bukkit.createInventory(null, 54, SF_CHEST_GUI_TITLE);
        if (item != null && item.hasItemMeta()) {
            byte[] bytes = item.getItemMeta().getPersistentDataContainer().get(itemChestDataKey, PersistentDataType.BYTE_ARRAY);
            if (bytes != null && bytes.length != 0) {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
                for (int i = 0; i != items.length && i != 54; i++) inv.setItem(i, items[i]);
            }
        }
        return inv;
    }
    private void saveHandheldSFChestInventory(Player player, int slot, Inventory inv) {
        ItemStack held = player.getInventory().getItem(slot);
        if (!isSFChestItem(held)) return;
        ItemMeta meta = held.getItemMeta();
        if (meta != null) {
            byte[] bytes = ItemStack.serializeItemsAsBytes(inv.getContents());
            meta.getPersistentDataContainer().set(itemChestDataKey, PersistentDataType.BYTE_ARRAY, bytes);
            held.setItemMeta(meta);
        }
    }
    private Inventory loadPlacedSFChestInventory(Block block) {
        Inventory inv = Bukkit.createInventory(null, 54, SF_CHEST_GUI_TITLE);
        NamespacedKey dataKey = new NamespacedKey(this, "sf_chest_data_" + block.getX() + "" + block.getY() + "" + block.getZ());
        byte[] bytes = block.getChunk().getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (bytes != null && bytes.length != 0) {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
            for (int i = 0; i != items.length && i != 54; i++) inv.setItem(i, items[i]);
        }
        return inv;
    }
    private void savePlacedSFChestInventory(Block block, Inventory inv) {
        if (!isSFChestBlock(block)) return;
        NamespacedKey dataKey = new NamespacedKey(this, "sf_chest_data_" + block.getX() + "" + block.getY() + "" + block.getZ());
        byte[] bytes = ItemStack.serializeItemsAsBytes(inv.getContents());
        block.getChunk().getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY, bytes);
    }
    private void saveActiveSFChest(Player player, Inventory topInv) {
        UUID uuid = player.getUniqueId();
        Integer heldSlot = (Integer) openHandheldSlots.get(uuid);
        if (heldSlot != null) {
            saveHandheldSFChestInventory(player, heldSlot, topInv);
            return;
        }
        Block openBlock = (Block) openPlacedChests.get(uuid);
        if (openBlock != null) {
            savePlacedSFChestInventory(openBlock, topInv);
        }
    }
    private void openCategoriesMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, GUIDE_CATEGORIES_TITLE);
        ItemStack filler = createPane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i != 27; i++) menu.setItem(i, filler);
        ItemStack catCrafter = new ItemStack(Material.TARGET);
        ItemMeta crafterMeta = catCrafter.getItemMeta();
        if (crafterMeta != null) {
            crafterMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "SF Crafting Table");
            crafterMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Opens in hand or when placed.",
                    ChatColor.GRAY + "Used to craft special SF Lite items.",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            crafterMeta.addEnchant(Enchantment.UNBREAKING, 10, true);
            catCrafter.setItemMeta(crafterMeta);
        }
        ItemStack catVeinPick = new ItemStack(Material.WOODEN_PICKAXE);
        ItemMeta pickMeta = catVeinPick.getItemMeta();
        if (pickMeta != null) {
            pickMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Pickaxe of Vein");
            pickMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Mines entire veins of ore at once.",
                    ChatColor.AQUA + "Diamond-tier mining power.",
                    ChatColor.DARK_GRAY + "SF Lite Tool",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            pickMeta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            pickMeta.addEnchant(Enchantment.UNBREAKING, 5, true);
            catVeinPick.setItemMeta(pickMeta);
        }
        ItemStack catSFChest = new ItemStack(Material.CYAN_SHULKER_BOX);
        ItemMeta chestMeta = catSFChest.getItemMeta();
        if (chestMeta != null) {
            chestMeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "SF Chest");
            chestMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "54-slot storage (Double Chest size).",
                    ChatColor.GRAY + "Opens in hand or when placed.",
                    ChatColor.GRAY + "Double-click any slot inside to auto-sort.",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            chestMeta.addEnchant(Enchantment.UNBREAKING, 10, true);
            catSFChest.setItemMeta(chestMeta);
        }
        ItemStack catHeroBoots = new ItemStack(Material.CHAINMAIL_BOOTS);
        ItemMeta bootsMeta = catHeroBoots.getItemMeta();
        if (bootsMeta != null) {
            bootsMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Super Hero Landing");
            bootsMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Transfers all fall damage to nearby",
                    ChatColor.GRAY + "mobs and players (bypasses PvP).",
                    ChatColor.YELLOW + "Single-use: breaks upon impact!",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            bootsMeta.addEnchant(Enchantment.FEATHER_FALLING, 1, true);
            catHeroBoots.setItemMeta(bootsMeta);
        }
        ItemStack catFrog = new ItemStack(Material.LEATHER_HELMET);
        LeatherArmorMeta frogMeta = (LeatherArmorMeta) catFrog.getItemMeta();
        if (frogMeta != null) {
            frogMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Frog");
            frogMeta.setColor(Color.GREEN);
            frogMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Grants " + ChatColor.AQUA + "+Night Vision" + ChatColor.GRAY + " while worn.",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            frogMeta.addEnchant(Enchantment.RESPIRATION, 3, true);
            catFrog.setItemMeta(frogMeta);
        }
        ItemStack catYondu = new ItemStack(Material.COPPER_SPEAR);
        ItemMeta yonduMeta = catYondu.getItemMeta();
        if (yonduMeta != null) {
            yonduMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Yondu Yaka");
            yonduMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Hold " + ChatColor.YELLOW + "Right-Click" + ChatColor.GRAY + " (up to 5s) to charge",
                    ChatColor.GRAY + "and whistle-launch with " + ChatColor.AQUA + "Riptide X" + ChatColor.GRAY + " power.",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            yonduMeta.addEnchant(Enchantment.RIPTIDE, 10, true);
            catYondu.setItemMeta(yonduMeta);
        }
        ItemStack catPortalGun = new ItemStack(Material.BREEZE_ROD);
        ItemMeta portalMeta = catPortalGun.getItemMeta();
        if (portalMeta != null) {
            portalMeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Portal Gun");
            portalMeta.setLore(Arrays.asList(
                    ChatColor.AQUA + "Left-Click" + ChatColor.GRAY + " to shoot " + ChatColor.AQUA + "Blue Portal",
                    ChatColor.GOLD + "Right-Click" + ChatColor.GRAY + " to shoot " + ChatColor.GOLD + "Orange Portal",
                    ChatColor.YELLOW + "Shift + Left-Click" + ChatColor.GRAY + " to clear portals",
                    "",
                    ChatColor.YELLOW + "Left-Click to show crafting recipe"
            ));
            portalMeta.addEnchant(Enchantment.UNBREAKING, 10, true);
            portalMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            catPortalGun.setItemMeta(portalMeta);
        }
        menu.setItem(10, catCrafter);
        menu.setItem(11, catVeinPick);
        menu.setItem(12, catSFChest);
        menu.setItem(13, catHeroBoots);
        menu.setItem(14, catFrog);
        menu.setItem(15, catYondu);
        menu.setItem(16, catPortalGun);
        player.openInventory(menu);
    }
    private void openRecipeViewer(Player player, int recipeId) {
        Inventory view = Bukkit.createInventory(null, 45, RECIPE_VIEW_TITLE);
        ItemStack filler = createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i != 45; i++) view.setItem(i, filler);
        int[] viewerGridSlots = {12, 13, 14, 21, 22, 23, 30, 31, 32};
        if (recipeId == 1) {
            view.setItem(19, createPane(Material.CRAFTING_TABLE, ChatColor.GREEN + "Craft in: Normal Crafting Table"));
            for (int slot : viewerGridSlots) view.setItem(slot, new ItemStack(Material.OAK_PLANKS));
            view.setItem(25, createSFCraftingTable());
        } else {
            ItemStack sfIcon = createSFCraftingTable();
            ItemMeta sfMeta = sfIcon.getItemMeta();
            if (sfMeta != null) {
                sfMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Craft in: SF Crafting Table");
                sfMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Craft inside the SF Crafting Table workstation."));
                sfIcon.setItemMeta(sfMeta);
            }
            view.setItem(19, sfIcon);
            if (recipeId == 2) {
                ItemStack[] pattern = {
                        new ItemStack(Material.DIAMOND), new ItemStack(Material.STICK), new ItemStack(Material.DIAMOND),
                        null, new ItemStack(Material.STICK), null,
                        null, new ItemStack(Material.STICK), null
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createVeinPickaxe());
            } else if (recipeId == 3) {
                ItemStack[] pattern = {
                        new ItemStack(Material.OAK_PLANKS), new ItemStack(Material.OAK_PLANKS), new ItemStack(Material.OAK_PLANKS),
                        new ItemStack(Material.OAK_PLANKS), new ItemStack(Material.DIAMOND), new ItemStack(Material.OAK_PLANKS),
                        new ItemStack(Material.OAK_PLANKS), new ItemStack(Material.OAK_PLANKS), new ItemStack(Material.OAK_PLANKS)
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createSFChest());
            } else if (recipeId == 4) {
                ItemStack[] pattern = {
                        new ItemStack(Material.RAW_COPPER), new ItemStack(Material.RAW_COPPER), new ItemStack(Material.RAW_COPPER),
                        new ItemStack(Material.COAL),       new ItemStack(Material.COAL),       new ItemStack(Material.COAL),
                        new ItemStack(Material.FLINT),      new ItemStack(Material.FLINT),      new ItemStack(Material.FLINT)
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createHeroBoots());
            } else if (recipeId == 5) {
                ItemStack[] pattern = {
                        new ItemStack(Material.GREEN_WOOL), new ItemStack(Material.GREEN_WOOL), new ItemStack(Material.GREEN_WOOL),
                        new ItemStack(Material.GREEN_WOOL), null,                               new ItemStack(Material.GREEN_WOOL),
                        null,                               null,                               null
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createFrogCap());
            } else if (recipeId == 6) {
                ItemStack[] pattern = {
                        new ItemStack(Material.PRISMARINE_SHARD), new ItemStack(Material.PRISMARINE_SHARD), new ItemStack(Material.PRISMARINE_SHARD),
                        new ItemStack(Material.PRISMARINE_SHARD), new ItemStack(Material.EMERALD),          null,
                        new ItemStack(Material.PRISMARINE_SHARD), null,                                     new ItemStack(Material.EMERALD)
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createYonduYaka());
            } else if (recipeId == 7) {
                ItemStack[] pattern = {
                        new ItemStack(Material.WATER_BUCKET), new ItemStack(Material.ENDER_PEARL),  new ItemStack(Material.WATER_BUCKET),
                        new ItemStack(Material.STONE_BUTTON), new ItemStack(Material.LAVA_BUCKET),  new ItemStack(Material.STONE_BUTTON),
                        new ItemStack(Material.WATER_BUCKET), new ItemStack(Material.OAK_BUTTON),   new ItemStack(Material.WATER_BUCKET)
                };
                for (int i = 0; i != 9; i++) view.setItem(viewerGridSlots[i], pattern[i]);
                view.setItem(25, createPortalGun());
            }
        }
        view.setItem(40, createPane(Material.BARRIER, ChatColor.RED + "← Back to Categories"));
        player.openInventory(view);
    }
    private void openSFCraftingGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 45, GUI_TITLE);
        ItemStack filler = createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i != 45; i++) gui.setItem(i, filler);
        for (int slot : GRID_SLOTS) gui.setItem(slot, null);
        gui.setItem(RESULT_SLOT, null);
        player.openInventory(gui);
    }
    private ItemStack createPane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
    private boolean isShulkerOrSFChest(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return isSFChestItem(item) || Tag.SHULKER_BOXES.isTagged(item.getType());
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        if (title.equals(GUIDE_CATEGORIES_TITLE)) {
            event.setCancelled(true);
            if (!event.isLeftClick()) return;
            if (event.getRawSlot() == 10) openRecipeViewer(player, 1);
            if (event.getRawSlot() == 11) openRecipeViewer(player, 2);
            if (event.getRawSlot() == 12) openRecipeViewer(player, 3);
            if (event.getRawSlot() == 13) openRecipeViewer(player, 4);
            if (event.getRawSlot() == 14) openRecipeViewer(player, 5);
            if (event.getRawSlot() == 15) openRecipeViewer(player, 6);
            if (event.getRawSlot() == 16) openRecipeViewer(player, 7);
            return;
        }
        if (title.equals(RECIPE_VIEW_TITLE)) {
            event.setCancelled(true);
            if (!event.isLeftClick()) return;
            if (event.getRawSlot() == 40) openCategoriesMenu(player);
            return;
        }
        Inventory topInv = event.getView().getTopInventory();
        if (title.equals(SF_CHEST_GUI_TITLE)) {
            UUID uuid = player.getUniqueId();
            Integer lockedSlot = (Integer) openHandheldSlots.get(uuid);
            if (lockedSlot != null) {
                if (event.getClickedInventory() == player.getInventory() && event.getSlot() == lockedSlot) {
                    event.setCancelled(true);
                    return;
                }
                if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == lockedSlot) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (isShulkerOrSFChest(event.getCurrentItem()) || isShulkerOrSFChest(event.getCursor())) {
                if (event.getRawSlot() == -999) return;
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                    return;
                }
                for (int s = 0; s != 54; s++) {
                    if (event.getRawSlot() == s) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            int rawSlot = event.getRawSlot();
            boolean isTopSlot = false;
            for (int s = 0; s != 54; s++) {
                if (rawSlot == s) { isTopSlot = true; break; }
            }
            if (isTopSlot) {
                long now = System.currentTimeMillis();
                Long lastObj = (Long) lastChestClickTime.get(uuid);
                long lastClick = lastObj != null ? lastObj : 0L;
                lastChestClickTime.put(uuid, now);
                if (event.getClick() == ClickType.DOUBLE_CLICK || (now - lastClick) == Math.min(now - lastClick, 399L)) {
                    lastChestClickTime.remove(uuid);
                    event.setCancelled(true);
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        topInv.addItem(cursor.clone());
                        event.setCursor(null);
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        sortInventory(topInv);
                        saveActiveSFChest(player, topInv);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
                    });
                    return;
                }
            }
            Bukkit.getScheduler().runTask(this, () -> saveActiveSFChest(player, topInv));
            return;
        }
        if (!title.equals(GUI_TITLE)) return;
        int rawSlot = event.getRawSlot();
        boolean isInGui = false;
        for (int s = 0; s != 45; s++) {
            if (rawSlot == s) { isInGui = true; break; }
        }
        if (isInGui) {
            boolean isGridSlot = false;
            for (int slot : GRID_SLOTS) {
                if (rawSlot == slot) { isGridSlot = true; break; }
            }
            if (!isGridSlot && rawSlot != RESULT_SLOT) {
                event.setCancelled(true);
                return;
            }
            if (rawSlot == RESULT_SLOT) {
                event.setCancelled(true);
                ItemStack result = topInv.getItem(RESULT_SLOT);
                if (result != null && result.getType() != Material.AIR) {
                    player.getInventory().addItem(result.clone());
                    for (int slot : GRID_SLOTS) {
                        ItemStack ing = topInv.getItem(slot);
                        if (ing != null) {
                            if (ing.getType() == Material.WATER_BUCKET || ing.getType() == Material.LAVA_BUCKET) {
                                player.getInventory().addItem(new ItemStack(Material.BUCKET));
                            }
                            ing.setAmount(ing.getAmount() - 1);
                        }
                    }
                    Bukkit.getScheduler().runTask(this, () -> updateCraftingResult(topInv));
                }
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, () -> updateCraftingResult(topInv));
    }
    private void updateCraftingResult(Inventory inv) {
        Material[] pickPattern = {
                Material.DIAMOND, Material.STICK, Material.DIAMOND,
                null, Material.STICK, null,
                null, Material.STICK, null
        };
        boolean matchesPick = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (actual != pickPattern[i]) { matchesPick = false; break; }
        }
        if (matchesPick) {
            inv.setItem(RESULT_SLOT, createVeinPickaxe());
            return;
        }
        boolean matchesChest = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (i == 4) {
                if (actual != Material.DIAMOND) { matchesChest = false; break; }
            } else {
                if (actual == null || !Tag.PLANKS.isTagged(actual)) { matchesChest = false; break; }
            }
        }
        if (matchesChest) {
            inv.setItem(RESULT_SLOT, createSFChest());
            return;
        }
        Material[] heroBootsPattern = {
                Material.RAW_COPPER, Material.RAW_COPPER, Material.RAW_COPPER,
                Material.COAL,       Material.COAL,       Material.COAL,
                Material.FLINT,      Material.FLINT,      Material.FLINT
        };
        boolean matchesHeroBoots = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (actual != heroBootsPattern[i]) { matchesHeroBoots = false; break; }
        }
        if (matchesHeroBoots) {
            inv.setItem(RESULT_SLOT, createHeroBoots());
            return;
        }
        Material[] frogPattern = {
                Material.GREEN_WOOL, Material.GREEN_WOOL, Material.GREEN_WOOL,
                Material.GREEN_WOOL, null,                Material.GREEN_WOOL,
                null,                null,                null
        };
        boolean matchesFrog = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (actual != frogPattern[i]) { matchesFrog = false; break; }
        }
        if (matchesFrog) {
            inv.setItem(RESULT_SLOT, createFrogCap());
            return;
        }
        Material[] yonduPattern = {
                Material.PRISMARINE_SHARD, Material.PRISMARINE_SHARD, Material.PRISMARINE_SHARD,
                Material.PRISMARINE_SHARD, Material.EMERALD,          null,
                Material.PRISMARINE_SHARD, null,                      Material.EMERALD
        };
        boolean matchesYondu = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (actual != yonduPattern[i]) { matchesYondu = false; break; }
        }
        if (matchesYondu) {
            inv.setItem(RESULT_SLOT, createYonduYaka());
            return;
        }
        boolean matchesPortal = true;
        for (int i = 0; i != 9; i++) {
            ItemStack item = inv.getItem(GRID_SLOTS[i]);
            Material actual = (item == null || item.getType() == Material.AIR) ? null : item.getType();
            if (i == 0 || i == 2 || i == 6 || i == 8) {
                if (actual != Material.WATER_BUCKET) { matchesPortal = false; break; }
            } else if (i == 1) {
                if (actual != Material.ENDER_PEARL) { matchesPortal = false; break; }
            } else if (i == 3 || i == 5) {
                if (actual != Material.STONE_BUTTON) { matchesPortal = false; break; }
            } else if (i == 4) {
                if (actual != Material.LAVA_BUCKET) { matchesPortal = false; break; }
            } else if (i == 7) {
                if (actual == null || !Tag.WOODEN_BUTTONS.isTagged(actual)) { matchesPortal = false; break; }
            }
        }
        if (matchesPortal) {
            inv.setItem(RESULT_SLOT, createPortalGun());
            return;
        }
        inv.setItem(RESULT_SLOT, null);
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();
        lastChestClickTime.remove(uuid);
        if (title.equals(SF_CHEST_GUI_TITLE)) {
            Integer heldSlot = (Integer) openHandheldSlots.remove(uuid);
            if (heldSlot != null) {
                saveHandheldSFChestInventory(player, heldSlot, event.getInventory());
                player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, 0.7f, 1.2f);
                return;
            }
            Block block = (Block) openPlacedChests.remove(uuid);
            if (block != null) {
                savePlacedSFChestInventory(block, event.getInventory());
                player.playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, 0.7f, 1.1f);
            }
            return;
        }
        if (!title.equals(GUI_TITLE)) return;
        Inventory inv = event.getInventory();
        for (int slot : GRID_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                player.getInventory().addItem(item);
            }
        }
    }
    private void sortInventory(Inventory inv) {
        ItemStack[] contents = inv.getContents();
        ItemStack[] merged = new ItemStack[contents.length];
        int count = 0;
        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR) continue;
            ItemStack remaining = item.clone();
            for (int i = 0; i != count; i++) {
                ItemStack existing = merged[i];
                if (existing.isSimilar(remaining) && existing.getAmount() != existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getAmount();
                    int transfer = Math.min(space, remaining.getAmount());
                    existing.setAmount(existing.getAmount() + transfer);
                    remaining.setAmount(remaining.getAmount() - transfer);
                    if (remaining.getAmount() == 0) break;
                }
            }
            if (remaining.getAmount() != 0) merged[count++] = remaining;
        }
        ItemStack[] result = Arrays.copyOf(merged, count);
        Arrays.sort(result, Comparator
                .comparing((ItemStack is) -> is.getType().name())
                .thenComparing(is -> is.hasItemMeta() && is.getItemMeta().hasDisplayName() ? is.getItemMeta().getDisplayName() : "")
                .thenComparing(Comparator.comparingInt(ItemStack::getAmount).reversed())
        );
        inv.clear();
        for (int i = 0; i != result.length && i != inv.getSize(); i++) {
            inv.setItem(i, result[i]);
        }
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block startBlock = event.getBlock();
        if (isSFCraftingTableBlock(startBlock)) {
            NamespacedKey blockKey = new NamespacedKey(this, "sf_table_" + startBlock.getX() + "" + startBlock.getY() + "" + startBlock.getZ());
            startBlock.getChunk().getPersistentDataContainer().remove(blockKey);
            event.setDropItems(false);
            startBlock.getWorld().dropItemNaturally(startBlock.getLocation(), createSFCraftingTable());
            return;
        }
        if (isSFChestBlock(startBlock)) {
            PersistentDataContainer pdc = startBlock.getChunk().getPersistentDataContainer();
            NamespacedKey blockKey = new NamespacedKey(this, "sf_chest_" + startBlock.getX() + "" + startBlock.getY() + "" + startBlock.getZ());
            NamespacedKey dataKey = new NamespacedKey(this, "sf_chest_data_" + startBlock.getX() + "" + startBlock.getY() + "" + startBlock.getZ());
            byte[] bytes = pdc.get(dataKey, PersistentDataType.BYTE_ARRAY);
            if (bytes != null && bytes.length != 0) {
                for (ItemStack content : ItemStack.deserializeItemsFromBytes(bytes)) {
                    if (content != null && content.getType() != Material.AIR) {
                        startBlock.getWorld().dropItemNaturally(startBlock.getLocation(), content);
                    }
                }
            }
            pdc.remove(blockKey);
            pdc.remove(dataKey);
            event.setDropItems(false);
            startBlock.getWorld().dropItemNaturally(startBlock.getLocation(), createSFChest());
            return;
        }
        if (processingBlocks.contains(startBlock)) return;
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isVeinPickaxe(tool) || !isOre(startBlock.getType())) return;
        if (player.isSneaking()) return;
        Block[] vein = findVein(startBlock, startBlock.getType());
        for (Block block : vein) {
            if (block.equals(startBlock)) continue;
            if (isToolBroken(tool)) break;
            processingBlocks.add(block);
            player.breakBlock(block);
            processingBlocks.remove(block);
        }
    }
    private boolean isVeinPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(pickaxeKey, PersistentDataType.BYTE);
    }
    private boolean isOre(Material material) {
        return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }
    private boolean isToolBroken(ItemStack tool) {
        if (tool.getItemMeta() instanceof Damageable damageable) {
            return damageable.getDamage() == tool.getType().getMaxDurability() - 1 || damageable.getDamage() == tool.getType().getMaxDurability();
        }
        return false;
    }
    private Block[] findVein(Block start, Material targetType) {
        Set visited = new HashSet();
        Queue queue = new LinkedList();
        queue.add(start);
        visited.add(start);
        BlockFace[] faces = {
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        };
        while (!queue.isEmpty() && visited.size() != MAX_VEIN_SIZE) {
            Block current = (Block) queue.poll();
            for (BlockFace face : faces) {
                Block neighbor = current.getRelative(face);
                if (!visited.contains(neighbor) && neighbor.getType() == targetType) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        Block[] result = new Block[visited.size()];
        int i = 0;
        for (Object obj : visited) result[i++] = (Block) obj;
        return result;
    }
}