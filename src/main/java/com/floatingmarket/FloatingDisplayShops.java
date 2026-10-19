package com.floatingmarket;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class FloatingDisplayShops extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private Economy economy;

    public NamespacedKey ownerKey;
    public NamespacedKey ownerNameKey;
    public NamespacedKey modeKey;
    public NamespacedKey priceKey;
    public NamespacedKey barterMatKey;
    public NamespacedKey itemBytesKey;
    public NamespacedKey adminShopKey;
    public NamespacedKey baseLocKey;

    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private final Map<UUID, Long> tapConfirmMap = new HashMap<>();

    private Object gpDataStore;
    private Method gpGetClaimAtMethod;

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "shop_owner");
        this.ownerNameKey = new NamespacedKey(this, "shop_owner_name");
        this.modeKey = new NamespacedKey(this, "shop_mode");
        this.priceKey = new NamespacedKey(this, "shop_price");
        this.barterMatKey = new NamespacedKey(this, "shop_barter_mat");
        this.itemBytesKey = new NamespacedKey(this, "shop_item_bytes");
        this.adminShopKey = new NamespacedKey(this, "shop_admin");
        this.baseLocKey = new NamespacedKey(this, "shop_base_loc");

        setupVault();
        setupGriefPrevention();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("shop") != null) {
            getCommand("shop").setExecutor(this);
            getCommand("shop").setTabCompleter(this);
        }
        if (getCommand("shopadmin") != null) {
            getCommand("shopadmin").setExecutor(this);
            getCommand("shopadmin").setTabCompleter(this);
        }
    }

    private void setupVault() {
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) this.economy = rsp.getProvider();
        }
    }

    private void setupGriefPrevention() {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) return;
        try {
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Field inst = gpClass.getField("instance");
            Object gpInst = inst.get(null);
            Field ds = gpClass.getField("dataStore");
            this.gpDataStore = ds.get(gpInst);
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
            this.gpGetClaimAtMethod = this.gpDataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
        } catch (Exception ignored) {
            this.gpDataStore = null;
        }
    }

    public boolean canBuildGP(Player player, Location loc) {
        if (gpDataStore == null || gpGetClaimAtMethod == null) return true;
        try {
            Object claim = gpGetClaimAtMethod.invoke(gpDataStore, loc, false, null);
            if (claim == null) return true;
            Method allowBuild = claim.getClass().getMethod("allowBuild", Player.class, Material.class);
            return allowBuild.invoke(claim, player, loc.getBlock().getType()) == null;
        } catch (Exception e) {
            return true;
        }
    }

    public String serializeLoc(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    public Interaction getShopHitboxAt(Block baseBlock) {
        String locTag = serializeLoc(baseBlock);
        Location center = baseBlock.getLocation().add(0.5, 1.0, 0.5);
        for (Entity e : baseBlock.getWorld().getNearbyEntities(center, 1.2, 1.5, 1.2)) {
            if (e instanceof Interaction interaction) {
                String tag = interaction.getPersistentDataContainer().get(baseLocKey, PersistentDataType.STRING);
                if (locTag.equals(tag)) return interaction;
            }
        }
        return null;
    }

    public boolean isShopBlock(Block block) {
        if (block == null) return false;
        if (block.getState() instanceof TileState tileState) {
            if (tileState.getPersistentDataContainer().has(baseLocKey, PersistentDataType.STRING)) return true;
        }
        return getShopHitboxAt(block) != null;
    }

    public void sendHelp(Player player, boolean isAdmin) {
        player.sendMessage(mm.deserialize("<gold>=== 3D Floating Shops Help ===</gold>"));
        player.sendMessage(mm.deserialize("<yellow>/shop create money <price> <amount> <item></yellow> <gray>- Sell items for money.</gray>"));
        player.sendMessage(mm.deserialize("<dark_gray>  Example: /shop create money 100 10 diamond</dark_gray>"));
        player.sendMessage(mm.deserialize("<yellow>/shop create barter <amount> <item> for <price> <currency></yellow> <gray>- Exchange items.</gray>"));
        player.sendMessage(mm.deserialize("<dark_gray>  Example: /shop create barter 10 diamond for 500 stone</dark_gray>"));
        player.sendMessage(mm.deserialize("<aqua>Tip:</aqua> <white>Type 'hand' instead of item name to sell your held item!</white>"));
        player.sendMessage(mm.deserialize("<yellow>/shop remove</yellow> <gray>- Delete the shop you are looking at.</gray>"));
        player.sendMessage(mm.deserialize("<yellow>/shop info</yellow> <gray>- View shop details.</gray>"));
        
        if (isAdmin) {
            player.sendMessage(mm.deserialize("<red>=== Admin Commands ===</red>"));
            player.sendMessage(mm.deserialize("<yellow>/shopadmin create money/barter ...</yellow> <gray>- Create Unlimited Server Shop.</gray>"));
            player.sendMessage(mm.deserialize("<yellow>/shopadmin remove</yellow> <gray>- Force remove any shop.</gray>"));
        }
    }

    public void createShop(Player creator, Block baseBlock, ItemStack product, String mode, double price, String barterMat, boolean isAdmin) {
        removeShopEntities(baseBlock);
        String locTag = serializeLoc(baseBlock);

        if (baseBlock.getState() instanceof TileState tileState) {
            tileState.getPersistentDataContainer().set(baseLocKey, PersistentDataType.STRING, locTag);
            tileState.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, creator.getUniqueId().toString());
            tileState.update();
        }

        Location spawnBase = baseBlock.getLocation().add(0.5, 1.0, 0.5);

        // Bedrock Fix: Billboard.VERTICAL always faces player without rotation logic
        ItemDisplay itemDisplay = (ItemDisplay) baseBlock.getWorld().spawnEntity(spawnBase.clone().add(0, 0.35, 0), EntityType.ITEM_DISPLAY);
        itemDisplay.setItemStack(product);
        itemDisplay.setViewRange(15.0f);
        itemDisplay.setBillboard(Display.Billboard.VERTICAL);
        itemDisplay.getPersistentDataContainer().set(baseLocKey, PersistentDataType.STRING, locTag);

        TextDisplay textDisplay = (TextDisplay) baseBlock.getWorld().spawnEntity(spawnBase.clone().add(0, 0.95, 0), EntityType.TEXT_DISPLAY);
        textDisplay.setViewRange(15.0f);
        textDisplay.setBillboard(Display.Billboard.VERTICAL);
        textDisplay.setDefaultBackground(false);
        textDisplay.setShadowed(true);
        textDisplay.getPersistentDataContainer().set(baseLocKey, PersistentDataType.STRING, locTag);

        Interaction hitbox = (Interaction) baseBlock.getWorld().spawnEntity(spawnBase, EntityType.INTERACTION);
        hitbox.setInteractionWidth(1.0f);
        hitbox.setInteractionHeight(1.6f);
        hitbox.setResponsive(true);

  PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, creator.getUniqueId().toString());
        pdc.set(ownerNameKey, PersistentDataType.STRING, creator.getName());
        pdc.set(modeKey, PersistentDataType.STRING, mode.toUpperCase());
        pdc.set(priceKey, PersistentDataType.DOUBLE, price);
        pdc.set(barterMatKey, PersistentDataType.STRING, barterMat.toUpperCase());
        pdc.set(itemBytesKey, PersistentDataType.BYTE_ARRAY, product.serializeAsBytes());
        pdc.set(adminShopKey, PersistentDataType.BYTE, (byte) (isAdmin ? 1 : 0));
        pdc.set(baseLocKey, PersistentDataType.STRING, locTag);

        updateHologram(baseBlock, hitbox);
    }

    public void updateHologram(Block baseBlock, Interaction hitbox) {
        if (hitbox == null) hitbox = getShopHitboxAt(baseBlock);
        if (hitbox == null) return;

        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        byte[] itemBytes = pdc.get(itemBytesKey, PersistentDataType.BYTE_ARRAY);
        if (itemBytes == null) return;

        ItemStack product = ItemStack.deserializeBytes(itemBytes);
        String mode = pdc.getOrDefault(modeKey, PersistentDataType.STRING, "MONEY");
        double price = pdc.getOrDefault(priceKey, PersistentDataType.DOUBLE, 10.0);
        String barterMat = pdc.getOrDefault(barterMatKey, PersistentDataType.STRING, "DIAMOND");
        String sellerName = pdc.getOrDefault(ownerNameKey, PersistentDataType.STRING, "Server");
        boolean isAdmin = pdc.getOrDefault(adminShopKey, PersistentDataType.BYTE, (byte) 0) == 1;

        int stockBundles = 0;
        if (!isAdmin && baseBlock.getState() instanceof Container container) {
            stockBundles = countMatchingItems(container.getInventory(), product) / Math.max(1, product.getAmount());
        }

        String itemName = product.getType().name().replace("_", " ");
        String productTitle = product.getAmount() + "x " + itemName;
        
        String costStr;
        if (mode.equalsIgnoreCase("MONEY")) {
            costStr = "<gold>💰 Price:</gold> $" + String.format("%.2f", price);
        } else {
            costStr = "<red>🔄 You Pay:</red> " + (int)price + "x " + barterMat.replace("_", " ");
        }

        String stockFormatted = isAdmin
                ? "<light_purple>Unlimited</light_purple>"
                : (stockBundles > 0 ? "<green>" + stockBundles + " left</green>" : "<red>OUT OF STOCK</red>");

        String holoText = "<yellow><bold>" + itemName.toUpperCase() + "</bold></yellow>\n"
                + "<green>📦 You Get:</green> " + productTitle + "\n"
                + costStr + "\n"
                + "<gray>Stock:</gray> " + stockFormatted + " <dark_gray>|</dark_gray> <gray>Seller:</gray> <white>" + (isAdmin ? "Server" : sellerName) + "</white>\n"
                + "<green>[Tap / Right-Click to Buy]</green>";

        String locTag = serializeLoc(baseBlock);
        Location center = baseBlock.getLocation().add(0.5, 1.0, 0.5);
        for (Entity e : baseBlock.getWorld().getNearbyEntities(center, 1.5, 2.0, 1.5)) {
            if (e instanceof TextDisplay td) {
                String tag = td.getPersistentDataContainer().get(baseLocKey, PersistentDataType.STRING);
                if (locTag.equals(tag)) td.text(mm.deserialize(holoText));
            }
        }
    }

    public void removeShopEntities(Block baseBlock) {
        String locTag = serializeLoc(baseBlock);
        if (baseBlock.getState() instanceof TileState tileState) {
            tileState.getPersistentDataContainer().remove(baseLocKey);
            tileState.getPersistentDataContainer().remove(ownerKey);
            tileState.update();
        }
        Location center = baseBlock.getLocation().add(0.5, 1.0, 0.5);
        for (Entity e : baseBlock.getWorld().getNearbyEntities(center, 1.8, 2.2, 1.8)) {
            String tag = e.getPersistentDataContainer().get(baseLocKey, PersistentDataType.STRING);
            if (locTag.equals(tag)) e.remove();
        }
    }

    public void processPurchase(Player buyer, Interaction hitbox) {
        long now = System.currentTimeMillis();
        if (now - cooldownMap.getOrDefault(buyer.getUniqueId(), 0L) < 300) return;
        cooldownMap.put(buyer.getUniqueId(), now);

        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String locTag = pdc.get(baseLocKey, PersistentDataType.STRING);
        byte[] itemBytes = pdc.get(itemBytesKey, PersistentDataType.BYTE_ARRAY);
        if (locTag == null || itemBytes == null) return;

        String[] parts = locTag.split(":");
        if (parts.length != 4) return;
        Block baseBlock = hitbox.getWorld().getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));

        ItemStack product = ItemStack.deserializeBytes(itemBytes);
        int bundleAmount = product.getAmount();
        boolean isAdmin = pdc.getOrDefault(adminShopKey, PersistentDataType.BYTE, (byte) 0) == 1;
        String ownerUuidStr = pdc.get(ownerKey, PersistentDataType.STRING);
        UUID ownerUuid = ownerUuidStr != null ? UUID.fromString(ownerUuidStr) : null;

        if (!isAdmin && ownerUuid != null && ownerUuid.equals(buyer.getUniqueId())) {
            buyer.sendMessage(mm.deserialize("<yellow>This is your own shop! Open the Barrel/Chest to add more stock.</yellow>"));
            return;
        }

        if (!hasInventorySpace(buyer.getInventory(), product, bundleAmount)) {
            buyer.sendMessage(mm.deserialize("<red>Your inventory is full! Clear space first.</red>"));
            return;
        }

        Container sellerContainer = null;
        if (!isAdmin) {
            if (!(baseBlock.getState() instanceof Container container)) {
                buyer.sendMessage(mm.deserialize("<red>Shop container missing!</red>"));
                return;
            }
            sellerContainer = container;
            if (countMatchingItems(sellerContainer.getInventory(), product) < bundleAmount) {
                buyer.sendMessage(mm.deserialize("<red>This shop is OUT OF STOCK!</red>"));
                updateHologram(baseBlock, hitbox);
                return;
            }
        }

        String mode = pdc.getOrDefault(modeKey, PersistentDataType.STRING, "MONEY");
        double price = pdc.getOrDefault(priceKey, PersistentDataType.DOUBLE, 10.0);
        String barterMatName = pdc.getOrDefault(barterMatKey, PersistentDataType.STRING, "DIAMOND");

        if ("MONEY".equalsIgnoreCase(mode)) {
            if (economy == null) {
                buyer.sendMessage(mm.deserialize("<red>Vault Economy is not enabled on this server!</red>"));
                return;
            }
            if (!economy.has(buyer, price)) {
                buyer.sendMessage(mm.deserialize("<red>You need <gold>$" + price + "</gold> to buy this!</red>"));
                return;
            }
            if (!isAdmin && sellerContainer != null) {
                if (!removeMatchingItems(sellerContainer.getInventory(), product, bundleAmount)) {
                    buyer.sendMessage(mm.deserialize("<red>Stock error occurred!</red>"));
                    return;
                }
            }
            economy.withdrawPlayer(buyer, price);
            if (!isAdmin && ownerUuid != null) {
                OfflinePlayer seller = Bukkit.getOfflinePlayer(ownerUuid);
                economy.depositPlayer(seller, price);
                if (seller.isOnline() && seller.getPlayer() != null) {
                    seller.getPlayer().sendMessage(mm.deserialize("<green>[Shop] " + buyer.getName() + " bought " + bundleAmount + "x " + product.getType().name() + " for $" + price + "!</green>"));
                }
            }
        } else {
            Material currencyMat = Material.matchMaterial(barterMatName);
            if (currencyMat == null) currencyMat = Material.DIAMOND;
            int costCount = (int) Math.max(1, price);
            ItemStack currencyTemplate = new ItemStack(currencyMat, 1);

            if (countMatchingItems(buyer.getInventory(), currencyTemplate) < costCount) {
                buyer.sendMessage(mm.deserialize("<red>You need <aqua>" + costCount + "x " + currencyMat.name() + "</aqua> to buy this!</red>"));
                return;
            }

            if (!isAdmin && sellerContainer != null) {
                if (!hasInventorySpace(sellerContainer.getInventory(), currencyTemplate, costCount)) {
                    buyer.sendMessage(mm.deserialize("<red>Seller's shop barrel is full and cannot accept payment!</red>"));
                    return;
                }
                if (!removeMatchingItems(sellerContainer.getInventory(), product, bundleAmount)) {
                    buyer.sendMessage(mm.deserialize("<red>This shop is out of stock!</red>"));
                    return;
                }
                sellerContainer.getInventory().addItem(new ItemStack(currencyMat, costCount));
            }

            removeMatchingItems(buyer.getInventory(), currencyTemplate, costCount);
            if (!isAdmin && ownerUuid != null) {
                OfflinePlayer seller = Bukkit.getOfflinePlayer(ownerUuid);
                if (seller.isOnline() && seller.getPlayer() != null) {
                    seller.getPlayer().sendMessage(mm.deserialize("<green>[Shop] " + buyer.getName() + " bought " + bundleAmount + "x " + product.getType().name() + " for " + costCount + "x " + currencyMat.name() + "!</green>"));
                }
            }
        }

        buyer.getInventory().addItem(product.clone());
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        buyer.sendMessage(mm.deserialize("<green>Successfully bought <yellow>" + bundleAmount + "x " + product.getType().name().replace("_", " ") + "</yellow>!</green>"));

        updateHologram(baseBlock, hitbox);
    }

    public int countMatchingItems(Inventory inv, ItemStack template) {
        int total = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && item.isSimilar(template)) total += item.getAmount();
        }
        return total;
    }

    public boolean removeMatchingItems(Inventory inv, ItemStack template, int amountNeeded) {
        if (countMatchingItems(inv, template) < amountNeeded) return false;
        int remaining = amountNeeded;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.isSimilar(template)) {
                int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);
                if (item.getAmount() <= 0) inv.setItem(i, null);
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
        return true;
    }

    public boolean hasInventorySpace(Inventory inv, ItemStack template, int amountToAdd) {
        int remaining = amountToAdd;
        int maxStack = template.getMaxStackSize();
        for (ItemStack item : inv.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                remaining -= maxStack;
            } else if (item.isSimilar(template) && item.getAmount() < maxStack) {
                remaining -= (maxStack - item.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        boolean isAdminCmd = command.getName().equalsIgnoreCase("shopadmin");
        if (isAdminCmd && !player.hasPermission("displayshop.admin")) {
            player.sendMessage(mm.deserialize("<red>You do not have admin permission!</red>"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player, isAdminCmd);
            return true;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        String sub = args[0].toLowerCase();

        if (sub.equals("create")) {
            if (targetBlock == null || targetBlock.getType() == Material.AIR) {
                player.sendMessage(mm.deserialize("<red>Look at a solid block within 5 blocks!</red>"));
                return true;
            }

            if (!isAdminCmd) {
                if (!canBuildGP(player, targetBlock.getLocation())) {
                    player.sendMessage(mm.deserialize("<red>GriefPrevention: You don't have permission to build a shop here!</red>"));
                    return true;
                }
                if (targetBlock.getType() != Material.BARREL && targetBlock.getType() != Material.CHEST) {
                    player.sendMessage(mm.deserialize("<red>Player shops must be placed on a <yellow>Barrel</yellow> or <yellow>Chest</yellow>!</red>"));
                    return true;
                }
            }

            if (isShopBlock(targetBlock)) {
                player.sendMessage(mm.deserialize("<red>A shop already exists on this block! Remove it first.</red>"));
                return true;
            }

            if (args.length < 2) {
                sendHelp(player, isAdminCmd);
                return true;
            }

            String mode = args[1].toLowerCase();

            if (mode.equals("money")) {
                // /shop create money <price> <amount> <item>
                if (args.length < 5) {
                    player.sendMessage(mm.deserialize("<red>Usage: /" + label + " create money <price> <amount> <item_or_hand></red>"));
                    return true;
                }

                double price;
                int amount;
                try {
                    price = Double.parseDouble(args[2]);
                    amount = Integer.parseInt(args[3]);
                    if (price <= 0 || amount <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    player.sendMessage(mm.deserialize("<red>Price and amount must be positive numbers!</red>"));
                    return true;
                }

                String itemName = args[4];
                ItemStack product;

          if (itemName.equalsIgnoreCase("hand")) {
                    product = player.getInventory().getItemInMainHand().clone();
                    if (product.getType() == Material.AIR) {
                        player.sendMessage(mm.deserialize("<red>You must hold an item to use 'hand'!</red>"));
                        return true;
                    }
                } else {
                    Material mat = Material.matchMaterial(itemName);
                    if (mat == null) {
                        player.sendMessage(mm.deserialize("<red>Invalid item name: " + itemName + "</red>"));
                        return true;
                    }
                    product = new ItemStack(mat);
                }
                product.setAmount(amount);

                createShop(player, targetBlock, product, "MONEY", price, "NONE", isAdminCmd);
                player.sendMessage(mm.deserialize("<green>Money Shop created! Restock the Barrel/Chest below.</green>"));
                return true;

            } else if (mode.equals("barter")) {
                // /shop create barter <amount> <item> for <price> <currency>
                if (args.length < 7 || !args[4].equalsIgnoreCase("for")) {
                    player.sendMessage(mm.deserialize("<red>Usage: /" + label + " create barter <amount> <item> for <price> <currency_item></red>"));
                    return true;
                }

                int sellAmount;
                double priceAmount;
                try {
                    sellAmount = Integer.parseInt(args[2]);
                    priceAmount = Double.parseDouble(args[5]);
                    if (sellAmount <= 0 || priceAmount <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    player.sendMessage(mm.deserialize("<red>Amounts must be positive numbers!</red>"));
                    return true;
                }

                String sellItemName = args[3];
                String currencyName = args[6];

                ItemStack product;
                if (sellItemName.equalsIgnoreCase("hand")) {
                    product = player.getInventory().getItemInMainHand().clone();
                    if (product.getType() == Material.AIR) {
                        player.sendMessage(mm.deserialize("<red>You must hold an item to use 'hand'!</red>"));
                        return true;
                    }
                } else {
                    Material mat = Material.matchMaterial(sellItemName);
                    if (mat == null) {
                        player.sendMessage(mm.deserialize("<red>Invalid sell item name: " + sellItemName + "</red>"));
                        return true;
                    }
                    product = new ItemStack(mat);
                }
                product.setAmount(sellAmount);

                Material currencyMat = Material.matchMaterial(currencyName);
                if (currencyMat == null) {
                    player.sendMessage(mm.deserialize("<red>Invalid currency item name: " + currencyName + "</red>"));
                    return true;
                }

                createShop(player, targetBlock, product, "BARTER", priceAmount, currencyMat.name(), isAdminCmd);
                player.sendMessage(mm.deserialize("<green>Barter Shop created! Restock the Barrel/Chest below.</green>"));
                return true;
            } else {
                player.sendMessage(mm.deserialize("<red>Shop mode must be 'money' or 'barter'.</red>"));
                return true;
            }
        }

        if (sub.equals("remove")) {
            if (targetBlock == null) return true;
            Interaction hitbox = getShopHitboxAt(targetBlock);
            if (hitbox == null && !isShopBlock(targetBlock)) {
                player.sendMessage(mm.deserialize("<red>No shop found on the block you are looking at!</red>"));
                return true;
            }

            if (!isAdminCmd && hitbox != null) {
                String ownerUuid = hitbox.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (ownerUuid != null && !ownerUuid.equals(player.getUniqueId().toString())) {
                    player.sendMessage(mm.deserialize("<red>You are not the owner of this shop!</red>"));
                    return true;
                }
            }

            removeShopEntities(targetBlock);
            player.sendMessage(mm.deserialize("<yellow>3D Floating Shop removed safely.</yellow>"));
            return true;
        }

        if (sub.equals("info")) {
            if (targetBlock == null) return true;
            Interaction hitbox = getShopHitboxAt(targetBlock);
            if (hitbox == null) {
                player.sendMessage(mm.deserialize("<red>Look at a shop base block to view info!</red>"));
                return true;
            }
            String seller = hitbox.getPersistentDataContainer().getOrDefault(ownerNameKey, PersistentDataType.STRING, "Unknown");
            String mode = hitbox.getPersistentDataContainer().getOrDefault(modeKey, PersistentDataType.STRING, "MONEY");
            double price = hitbox.getPersistentDataContainer().getOrDefault(priceKey, PersistentDataType.DOUBLE, 0.0);
            player.sendMessage(mm.deserialize("<gold>Shop Seller:</gold> <white>" + seller + "</white> | <gold>Mode:</gold> <white>" + mode + " (" + price + ")</white>"));
            return true;
        }

        sendHelp(player, isAdminCmd);
        return true;
    }

    @EventHandler
    public void onEntityRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof Interaction hitbox) {
            if (hitbox.getPersistentDataContainer().has(baseLocKey, PersistentDataType.STRING)) {
                event.setCancelled(true);
                processPurchase(event.getPlayer(), hitbox);
            }
        }
    }

    @EventHandler
    public void onEntityTap(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction hitbox && event.getDamager() instanceof Player player) {
            if (!hitbox.getPersistentDataContainer().has(baseLocKey, PersistentDataType.STRING)) return;
            event.setCancelled(true);

            long now = System.currentTimeMillis();
            long lastTap = tapConfirmMap.getOrDefault(player.getUniqueId(), 0L);

            if (now - lastTap <= 2500L) {
                tapConfirmMap.remove(player.getUniqueId());
                processPurchase(player, hitbox);
            } else {
                tapConfirmMap.put(player.getUniqueId(), now);
                player.sendActionBar(mm.deserialize("<yellow>Tap / Hit again within 2.5s to confirm purchase!</yellow>"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || !isShopBlock(block)) return;

        Interaction hitbox = getShopHitboxAt(block);
        if (hitbox == null) return;

        Player player = event.getPlayer();
        String ownerUuid = hitbox.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        boolean isOwner = ownerUuid != null && ownerUuid.equals(player.getUniqueId().toString());

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!isOwner && !player.hasPermission("displayshop.admin")) {
                event.setCancelled(true);
                processPurchase(player, hitbox);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Container container) {
            Block block = container.getBlock();
            if (isShopBlock(block)) {
                Bukkit.getScheduler().runTaskLater(this, () -> updateHologram(block, null), 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Container c && isShopBlock(c.getBlock())) {
            event.setCancelled(true);
        }
        if (event.getDestination().getHolder() instanceof Container c && isShopBlock(c.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isShopBlock(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(mm.deserialize("<red>You cannot break a Shop block directly! Use <yellow>/shop remove</yellow> first.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (placed.getType() == Material.CHEST || placed.getType() == Material.TRAPPED_CHEST) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (isShopBlock(placed.getRelative(face))) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(mm.deserialize("<red>Cannot place a chest next to an active 3D Shop!</red>"));
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isShopBlock)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isShopBlock)) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "remove", "info", "help");
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) return Arrays.asList("money", "barter");
        
        // Auto-complete materials for parameters
        if (args.length == 5 && args[0].equalsIgnoreCase("create") && args[1].equalsIgnoreCase("money")) {
            List<String> mats = getMats(args[4]);
            mats.add(0, "hand");
            return mats;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create") && args[1].equalsIgnoreCase("barter")) {
            List<String> mats = getMats(args[3]);
            mats.add(0, "hand");
            return mats;
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("create") && args[1].equalsIgnoreCase("barter")) return Collections.singletonList("for");
        if (args.length == 7 && args[0].equalsIgnoreCase("create") && args[1].equalsIgnoreCase("barter")) {
            return getMats(args[6]);
        }
        return Collections.emptyList();
    }

    private List<String> getMats(String prefix) {
        String p = prefix.toUpperCase();
        return Arrays.stream(Material.values())
                .filter(Material::isItem)
                .map(Enum::name)
                .filter(name -> name.startsWith(p))
                .limit(15)
                .collect(Collectors.toList());
    }
        }
