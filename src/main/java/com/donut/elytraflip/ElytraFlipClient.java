package com.donut.elytraflip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {
    public static String ahSearchCmd = "ah elytra mending", autoPayTarget = "";
    public static double minProfit = 2_000_000, stopLossReduction = 1_000_000, outbidSpreadThreshold = 3_000_000, outbidStep = 1_000_000;
    public static double maxCapital = 100_000_000_000.0, ahTaxPercent = 0.0, autoPayThreshold = 10_000_000, autoPayAmount = 10_000_000;
    public static int stopLossCompetitorCount = 3, minElytraPerPage = 3, orderQuantity = 1, ahYourItemsCheckSec = 25;
    public static int refreshIntervalSec = 60, discountWaitSec = 150, retryScanSec = 7, volatilePauseSec = 300, minRandomTick = 8, maxRandomTick = 13;
    public static boolean showHud = true, noGraphicsMode = true, antiAfkEnabled = true, autoLogOnPlayer = false;
    public static boolean autoPayEnabled = false, autoOutbidEnabled = true, enableAhPage2 = true, autoDiscountEnabled = true, antiVolatileEnabled = true;

    public static int slotYourOrdersBtn = 51, slotNewOrderBtn = 9, slotChooseItemBtn = 12, slotSearchSignBtn = 50;
    public static int slotEnchantUnbreaking = 36, slotEnchantMending = 19, slotConfirmEnchant = 52, slotSetAmountBtn = 13, slotSetPriceBtn = 14;
    public static int slotConfirmNewOrder = 16, slotEditOrderCollectBox = 13, slotEditOrderCancelBtn = 14, slotCancelOrderConfirmBtn = 16;
    public static int slotAhYourItemsBtn = 51, slotAhConfirmBtn = 11, slotAhNextPageBtn = 53, slotBountyConfirmBtn = 15;

    private static final Box HIDDEN_CHUNK_BOX = new Box(0, -100000, 0, 0, -99999, 0);
    private static KeyBinding toggleKey, menuKey;
    private final List<Field> cachedParticleFields = new ArrayList<>();
    private final List<Field> cachedWorldCollectionFields = new ArrayList<>();
    private Field chunkStorageField = null, builtChunkArrayField = null, builtChunkBoxField = null, builtChunkDataField = null;
    private Object emptyChunkDataInstance = null;
    private boolean particleFieldsCached = false, worldFieldsCached = false, blocksCurrentlyStripped = false;
    private boolean configLoaded = false, clickedNextPage = false, forceDiscountRelist = false;
    private boolean running = false, hasActiveOrder = false, hasActiveAhListing = false, needAhRelist = false, forceAhRefresh60s = false, verifyingStabilityAfterPause = false;
    private boolean chatOrderCompletedFlag = false, chatAhSoldFlag = false, chatNotEnoughMoneyFlag = false;
    private BotState state = BotState.IDLE, nextStateAfterSign = BotState.IDLE, stateAfterBounty = BotState.SCAN_AH_OPEN;
    private String pendingSignText = "", pendingBountyCmd = "", statusText = "§cĐã Tắt";
    private int waitTicks = 0, guiWaitRetries = 0, ahWatchLoops = 0, moneyWaitLoops = 0, cmdCooldownTicks = 0;
    private int lastReadSyncId = -1, guiSettleTicks = 0, maintenanceTickCounter = 0;
    private int antiAfkJumpTimer = 0, antiAfkJumpHoldTicks = 0, nextRandomJumpTarget = 180;
    private int monitorTicksElapsed = 0, nextCheckTickTarget = 500, volatileTicksRemaining = 0, soldCount = 0;
    private int currentRefreshTargetTicks = 1200, currentDiscountWaitTargetSec = 150;
    private int currentAhWatchMaxLoops = 16, currentMoneyWaitMaxLoops = 15;
    private int page1CountAtOrBelow = 0, page1CompetitorsBelowTarget = 0, enchantRetryCount = 0;
    private long lastPriceSnapshotHash = -1, lastClickTimeMs = 0, nextAllowedClickDelayMs = 420, lastUsedDelayMs = 0, ahListingStartMs = 0;
    private long accumulatedActiveMs = 0, lastResumeTimeMs = 0, lastSaleRecordedMs = 0;
    private double unpaidProfitPool = 0, ahPriceDropTotal = 0, page1LowestAh = -1, lowestAhPrice = -1, highestOrderPrice = -1;
    private double myOrderPrice = 0, finalAhSellPrice = 0, myListedAhPrice = 0, pendingRelistPrice = 0, lastFailedOrderPrice = -1, lastProfit = 0, totalProfit = 0;

    private final List<Double> recentAhPrices = new ArrayList<>(), recentOrderPrices = new ArrayList<>();
    private final Random random = new Random();
    private static final Pattern EACH_PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?\\s*(?:each|/item|/ea)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_PRICE_PATTERN = Pattern.compile("(?:price|giá|cost|buy|worth)\\s*:?\\s*\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?");
    private static final Pattern AH_BOUGHT_CHAT_PATTERN = Pattern.compile("(?:bought|purchased)\\s+your\\s+(?:[0-9]+x?\\s+)?elytra\\s+for\\s*\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?", Pattern.CASE_INSENSITIVE);

    private enum BotState {
        IDLE, SCAN_AH_OPEN, SCAN_AH_READ, SCAN_ORDER_OPEN, SCAN_ORDER_READ, EVALUATE_INITIAL_PROFIT,
        WAIT_ENOUGH_MONEY_OPEN, WAIT_ENOUGH_MONEY_READ, PAUSE_VOLATILE_MARKET,
        ORDER_CLICK_YOUR_ORDERS, ORDER_CLICK_NEW_ORDER, ORDER_CLICK_ITEM_SLOT, ORDER_CLICK_SEARCH_SIGN,
        ORDER_SELECT_ELYTRA, ORDER_ENCH_UNBREAKING, ORDER_ENCH_MENDING, ORDER_ENCH_CONFIRM,
        ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, ORDER_CHECK_AMOUNT, ORDER_FINAL_CONFIRM, ORDER_POST_CONFIRM_CLOSE,
        SUBMIT_SIGN_GUI, MONITOR_WAIT, MONITOR_AH_OPEN, MONITOR_AH_READ,
        CLAIM_OPEN_ORDER, CLAIM_CLICK_YOUR_ORDERS, CLAIM_CLICK_COMPLETED_ELYTRA, CLAIM_CLICK_COLLECT_BOX, CLAIM_TAKE_ELYTRA_TO_HOTBAR, CLAIM_CLOSE_AND_WAIT_INV,
        CANCEL_ORDER_OPEN, CANCEL_ORDER_CLICK_YOURS, CANCEL_ORDER_CLICK_ELYTRA, CANCEL_ORDER_CLICK_RED_CANCEL, CANCEL_ORDER_CLICK_GREEN_CONFIRM,
        SELL_SCAN_AH_OPEN, SELL_SCAN_AH_READ, SELL_SELECT_HOTBAR_SLOT, SELL_SEND_AH_CMD, SELL_CONFIRM_AH_GUI,
        AH_MONITOR_WAIT, AH_CHECK_OPEN, AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, AH_YOUR_ITEMS_INSPECT, AH_WAIT_RELIST_INV,
        BOUNTY_SEND_CMD, BOUNTY_CLICK_GREEN_CONFIRM
    }
// === HẾT PHẦN 1 ===
        @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Bật/Tắt Auto Flip (F6)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "Donut Elytra Flip"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Mở Bảng Settings (F9)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "Donut Elytra Flip"));
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientReceiveMessageEvents.GAME.register((m, o) -> onChatMessage(m));
        ClientReceiveMessageEvents.CHAT.register((m, s, sd, p, t) -> onChatMessage(m));
    }

    private int rollNextRefreshTargetTicks() {
        int jitterSec = -6 + random.nextInt(16);
        return Math.max(18, refreshIntervalSec + jitterSec) * 20 + random.nextInt(20);
    }

    private int rollNextDiscountWaitSec() {
        int jitterSec = -8 + random.nextInt(23);
        return Math.max(20, discountWaitSec + jitterSec);
    }

    private int rollNextYourItemsCheckDeltaTicks() {
        int jitterSec = -4 + random.nextInt(11);
        return Math.max(12, ahYourItemsCheckSec + jitterSec) * 20 + random.nextInt(20);
    }

    private int rollRetryScanMinTicks() {
        int jitterSec = -2 + random.nextInt(5);
        return Math.max(4, retryScanSec + jitterSec) * 20 + random.nextInt(15);
    }

    private void onChatMessage(Text message) {
        if (message == null) return;
        String plain = message.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim(), lower = plain.toLowerCase();
        if (running && (lower.contains("your elytra order is complete") || lower.contains("delivered you 1 elytra"))) chatOrderCompletedFlag = true;
        if (running && lower.contains("you listed 1 elytra")) hasActiveAhListing = true;
        if (running && (lower.contains("not enough money") || lower.contains("don't have enough") || lower.contains("do not have enough") || lower.contains("insufficient") || lower.contains("can't afford") || lower.contains("cannot afford"))) chatNotEnoughMoneyFlag = true;
        Matcher bm = AH_BOUGHT_CHAT_PATTERN.matcher(plain);
        if (bm.find() && System.currentTimeMillis() - lastSaleRecordedMs > 1500) {
            double soldPrice = parseAmountWithSuffix(bm.group(1), bm.group(2));
            if (soldPrice > 0) {
                lastSaleRecordedMs = System.currentTimeMillis();
                recordAhSaleFromChat(MinecraftClient.getInstance(), soldPrice);
                hasActiveAhListing = false; hasActiveOrder = false; lastFailedOrderPrice = -1; chatAhSoldFlag = true;
            }
        }
    }

    private boolean isSellingOrAhMonitoringState() {
        return state == BotState.SELL_SCAN_AH_OPEN || state == BotState.SELL_SCAN_AH_READ || state == BotState.SELL_SELECT_HOTBAR_SLOT
                || state == BotState.SELL_SEND_AH_CMD || state == BotState.SELL_CONFIRM_AH_GUI || state == BotState.CLAIM_CLOSE_AND_WAIT_INV
                || state == BotState.AH_MONITOR_WAIT || state == BotState.AH_CHECK_OPEN || state == BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS
                || state == BotState.AH_YOUR_ITEMS_INSPECT || state == BotState.AH_WAIT_RELIST_INV
                || state == BotState.BOUNTY_SEND_CMD || state == BotState.BOUNTY_CLICK_GREEN_CONFIRM;
    }

    private void resetAhPagination() {
        clickedNextPage = false; page1LowestAh = -1; page1CountAtOrBelow = 0; page1CompetitorsBelowTarget = 0;
        lastPriceSnapshotHash = -1; guiSettleTicks = 0;
        currentAhWatchMaxLoops = 13 + random.nextInt(7);
        currentMoneyWaitMaxLoops = 12 + random.nextInt(7);
    }

    private void releaseAllAfkKeys(MinecraftClient c) {
        if (c == null || c.options == null) return;
        if (antiAfkJumpHoldTicks > 0) {
            c.options.jumpKey.setPressed(false);
            antiAfkJumpHoldTicks = 0;
        }
    }

    private void tickSmartAntiAfk(MinecraftClient c) {
        if (!antiAfkEnabled || c.player == null || c.options == null || c.currentScreen != null) {
            releaseAllAfkKeys(c);
            return;
        }
        if (antiAfkJumpHoldTicks > 0) {
            antiAfkJumpHoldTicks--;
            c.options.jumpKey.setPressed(antiAfkJumpHoldTicks > 0);
            return;
        }
        if (++antiAfkJumpTimer >= nextRandomJumpTarget) {
            antiAfkJumpTimer = 0;
            nextRandomJumpTarget = 135 + random.nextInt(245);
            antiAfkJumpHoldTicks = 2 + random.nextInt(3);
            c.options.jumpKey.setPressed(true);
            if (c.player.isOnGround()) c.player.jump();
        }
    }

    public void toggleBotRunning(MinecraftClient client) {
        running = !running;
        if (running) {
            lastResumeTimeMs = System.currentTimeMillis();
            recentAhPrices.clear(); recentOrderPrices.clear();
            verifyingStabilityAfterPause = false; lastFailedOrderPrice = -1;
            nextRandomJumpTarget = 110 + random.nextInt(190);
            currentRefreshTargetTicks = rollNextRefreshTargetTicks();
            currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
            logChat(client, "§aĐã BẬT Auto Flip (Tắt Hết Block + Full Random Delay)!");
            restartCycleSafeHuman(client, "§aĐang khởi động...");
        } else {
            releaseAllAfkKeys(client);
            accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs);
            state = BotState.IDLE; statusText = "§cĐã Tắt";
            logChat(client, "§cĐã TẮT Auto Flip Elytra!");
        }
    }

    private void restartCycleSafeHuman(MinecraftClient client, String reasonMsg) {
        releaseAllAfkKeys(client);
        chatAhSoldFlag = false; chatOrderCompletedFlag = false; chatNotEnoughMoneyFlag = false;
        hasActiveOrder = false; hasActiveAhListing = false; forceAhRefresh60s = false;
        needAhRelist = false; forceDiscountRelist = false; ahListingStartMs = 0; ahPriceDropTotal = 0;
        resetAhPagination(); lowestAhPrice = -1; highestOrderPrice = -1; lastReadSyncId = -1; enchantRetryCount = 0;
        guiWaitRetries = 0; ahWatchLoops = 0; moneyWaitLoops = 0; monitorTicksElapsed = 0;
        currentRefreshTargetTicks = rollNextRefreshTargetTicks();
        currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
        if (client.currentScreen != null && !(client.currentScreen instanceof FlipConfigScreen) && client.player != null) client.player.closeHandledScreen();
        if (!pendingBountyCmd.isEmpty()) {
            stateAfterBounty = BotState.SCAN_AH_OPEN;
            transitionRandom(BotState.BOUNTY_SEND_CMD, 10, 18, "§aChuẩn bị /bounty add...");
            return;
        }
        cmdCooldownTicks = Math.max(cmdCooldownTicks, 20 + random.nextInt(20));
        transitionRandom(BotState.SCAN_AH_OPEN, 20, 36, reasonMsg);
    }

    private boolean checkAutoLogOnPlayer(MinecraftClient client) {
        if (!autoLogOnPlayer || client.world == null || client.player == null) return false;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == null || other == client.player || other.getUuid().equals(client.player.getUuid())) continue;
            String otherName = other.getName().getString().trim();
            if (otherName.isEmpty() || (!autoPayTarget.isBlank() && otherName.equalsIgnoreCase(autoPayTarget.trim()))) continue;
            if (client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerListEntry(other.getUuid()) == null) continue;
            int dist = (int) Math.round(client.player.distanceTo(other));
            releaseAllAfkKeys(client);
            if (running) { accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs); running = false; }
            state = BotState.IDLE; statusText = "§cAuto Log (Gặp " + otherName + ")";
            if (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection() != null)
                client.getNetworkHandler().getConnection().disconnect(Text.literal("§6§l⚡ [AUTO LOG BẢO VỆ] ⚡\n\n§fPhát hiện người chơi: §c§l" + otherName + " §7(Cách §e" + dist + "m§7)\n§aĐã tự động thoát khỏi server an toàn!"));
            return true;
        }
        return false;
    }
// === HẾT PHẦN 2 ===
         // TẮT SẠCH 100% MỌI BLOCK, PISTON, RƯƠNG & HẠT HIỆU ỨNG TRONG WORLD RENDERER (GIỮ NGUYÊN VA CHẠM DƯỚI CHÂN)
    public void restoreWorldBlocks(MinecraftClient client) {
        if (!blocksCurrentlyStripped || client == null) return;
        blocksCurrentlyStripped = false;
        try {
            if (client.gameRenderer != null) {
                client.gameRenderer.setRenderHand(true);
                client.gameRenderer.setBlockOutlineEnabled(true);
            }
            if (client.worldRenderer != null) client.worldRenderer.reload();
        } catch (Exception ignored) {}
    }

    private void stripWorldGraphicsDirectly(MinecraftClient client) {
        if (!noGraphicsMode || client.world == null) {
            if (blocksCurrentlyStripped) restoreWorldBlocks(client);
            return;
        }
        try {
            blocksCurrentlyStripped = true;
            client.world.setRainGradient(0.0f);
            client.world.setThunderGradient(0.0f);
            if (client.gameRenderer != null) {
                client.gameRenderer.setRenderHand(false);
                client.gameRenderer.setBlockOutlineEnabled(false);
            }
            if (!particleFieldsCached && client.particleManager != null) {
                particleFieldsCached = true;
                for (Field f : ParticleManager.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType()) || Queue.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true); cachedParticleFields.add(f);
                    }
                }
            }
            if (maintenanceTickCounter % (8 + random.nextInt(5)) == 0 && client.particleManager != null) {
                for (Field f : cachedParticleFields) {
                    Object obj = f.get(client.particleManager);
                    if (obj instanceof Map<?, ?> m && !m.isEmpty()) m.clear();
                    else if (obj instanceof Collection<?> c && !c.isEmpty()) c.clear();
                }
            }
            if (client.worldRenderer == null) return;
            if (!worldFieldsCached) {
                worldFieldsCached = true;
                for (Field wf : WorldRenderer.class.getDeclaredFields()) {
                    if (Modifier.isStatic(wf.getModifiers())) continue;
                    wf.setAccessible(true);
                    if (Collection.class.isAssignableFrom(wf.getType())) {
                        cachedWorldCollectionFields.add(wf);
                    } else if (!wf.getType().isPrimitive()) {
                        for (Field cf : wf.getType().getDeclaredFields()) {
                            if (cf.getType().isArray() && !cf.getType().getComponentType().isPrimitive()) {
                                cf.setAccessible(true);
                                chunkStorageField = wf;
                                builtChunkArrayField = cf;
                                Class<?> bcClass = cf.getType().getComponentType();
                                for (Field bf : bcClass.getDeclaredFields()) {
                                    bf.setAccessible(true);
                                    if (bf.getType() == Box.class) builtChunkBoxField = bf;
                                    else if (bf.getType() == AtomicReference.class) builtChunkDataField = bf;
                                }
                                break;
                            }
                        }
                    }
                }
            }
            for (Field colField : cachedWorldCollectionFields) {
                Object col = colField.get(client.worldRenderer);
                if (col instanceof Collection<?> c && !c.isEmpty()) c.clear();
            }
            if (maintenanceTickCounter % 15 == 1 && chunkStorageField != null && builtChunkArrayField != null) {
                Object storage = chunkStorageField.get(client.worldRenderer);
                if (storage != null) {
                    Object[] chunks = (Object[]) builtChunkArrayField.get(storage);
                    if (chunks != null) {
                        for (Object bc : chunks) {
                            if (bc == null) continue;
                            if (builtChunkBoxField != null) builtChunkBoxField.set(bc, HIDDEN_CHUNK_BOX);
                            if (builtChunkDataField != null) {
                                AtomicReference<?> ref = (AtomicReference<?>) builtChunkDataField.get(bc);
                                if (ref != null) {
                                    Object curData = ref.get();
                                    if (emptyChunkDataInstance == null && curData != null) {
                                        for (Field df : curData.getClass().getDeclaredFields()) {
                                            if (Modifier.isStatic(df.getModifiers()) && df.getType() == curData.getClass()) {
                                                df.setAccessible(true);
                                                emptyChunkDataInstance = df.get(null);
                                                break;
                                            }
                                        }
                                    }
                                    if (emptyChunkDataInstance != null && curData != emptyChunkDataInstance) {
                                        ((AtomicReference<Object>) ref).set(emptyChunkDataInstance);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (!configLoaded) { configLoaded = true; loadConfig(client); }
        if (cmdCooldownTicks > 0) cmdCooldownTicks--;
        if (++maintenanceTickCounter >= 16000 + random.nextInt(4000)) {
            maintenanceTickCounter = 0; recentAhPrices.clear(); recentOrderPrices.clear(); lastReadSyncId = -1;
        }
        stripWorldGraphicsDirectly(client);
        if (running && checkAutoLogOnPlayer(client)) return;

        while (menuKey.wasPressed()) {
            releaseAllAfkKeys(client);
            if (client.currentScreen instanceof FlipConfigScreen s) s.close();
            else client.setScreen(new FlipConfigScreen(this));
            return;
        }
        while (toggleKey.wasPressed()) {
            if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT)) {
                soldCount = 0; lastProfit = 0; totalProfit = 0; unpaidProfitPool = 0; accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
            }
            toggleBotRunning(client);
        }
        if (!running || state == BotState.IDLE || client.currentScreen instanceof FlipConfigScreen) { releaseAllAfkKeys(client); return; }
        tickSmartAntiAfk(client);

        if (chatNotEnoughMoneyFlag) {
            chatNotEnoughMoneyFlag = false; hasActiveOrder = false;
            if (myOrderPrice > 0) lastFailedOrderPrice = myOrderPrice;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            double bal = getPlayerBalance(client);
            logChat(client, "§c§l[Không Đủ Tiền] §eTiền có: §a" + (bal > 0 ? formatMoney(bal) : "Thiếu") + " §c< Cần: §b" + formatMoney(myOrderPrice) + "§c! Đang canh giá Order...");
            transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 20, 36, "§cKhông đủ tiền! Đang canh giá Order...");
            return;
        }
        if (hasActiveOrder && (state == BotState.MONITOR_WAIT || state == BotState.MONITOR_AH_OPEN || state == BotState.MONITOR_AH_READ || state == BotState.ORDER_POST_CONFIRM_CLOSE)) monitorTicksElapsed++;
        if (chatAhSoldFlag) { restartCycleSafeHuman(client, "§aĐã bán xong! Bắt đầu vòng mới..."); return; }
        if (!hasActiveAhListing && !isSellingOrAhMonitoringState() && findElytraInInventory(client) != -1) {
            chatOrderCompletedFlag = false; hasActiveOrder = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            transitionRandom(BotState.SELL_SCAN_AH_OPEN, 20, 34, "§dCó Elytra -> Chuẩn bị bán AH...");
            return;
        }
        if (chatOrderCompletedFlag && !hasActiveAhListing && !isSellingOrAhMonitoringState() && state != BotState.CLAIM_OPEN_ORDER && state != BotState.CLAIM_CLICK_YOUR_ORDERS && state != BotState.CLAIM_CLICK_COMPLETED_ELYTRA && state != BotState.CLAIM_CLICK_COLLECT_BOX && state != BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR) {
            chatOrderCompletedFlag = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            transitionRandom(BotState.CLAIM_OPEN_ORDER, 18, 32, "§aĐơn xong! Mở /order...");
            return;
        }
        if (waitTicks > 0) { waitTicks--; return; }
        if (!tickScanAndCreateOrder(client)) tickMonitorClaimAndSell(client);
    }
// === HẾT PHẦN 3 ===
            private boolean tickScanAndCreateOrder(MinecraftClient client) {
        switch (state) {
            case PAUSE_VOLATILE_MARKET -> {
                if (client.currentScreen != null) client.player.closeHandledScreen();
                if (volatileTicksRemaining > 0) {
                    volatileTicksRemaining--;
                    statusText = "§cGiá loạn! Chờ (" + (volatileTicksRemaining / 20) + "s)";
                    return true;
                }
                recentAhPrices.clear(); recentOrderPrices.clear(); verifyingStabilityAfterPause = true;
                transitionRandom(BotState.SCAN_AH_OPEN, 20, 36, "§eKiểm tra lại giá...");
                return true;
            }
            case WAIT_ENOUGH_MONEY_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 20, 34, "§cĐóng bảng cũ...");
                    return true;
                }
                if (!sendCommandSafe(client, "order ely")) {
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 7, 14, statusText);
                    return true;
                }
                guiWaitRetries = 0; moneyWaitLoops = 0; resetAhPagination();
                transitionRandom(BotState.WAIT_ENOUGH_MONEY_READ, 10, 17, "§eXem giá Order chờ đủ tiền...");
                return true;
            }
            case WAIT_ENOUGH_MONEY_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    if (isGuiFullySettled(client, s) && hasAnyElytraWithPrice(client, s)) {
                        guiWaitRetries = 0;
                        double price = scanOrderHighestPrice(client, s);
                        if (price > 0) {
                            highestOrderPrice = price;
                            double rawDiff = (lowestAhPrice > 0) ? (lowestAhPrice - highestOrderPrice) : 0;
                            double req = (autoOutbidEnabled && lowestAhPrice > 0 && Math.round(rawDiff) >= Math.round(outbidSpreadThreshold))
                                    ? (highestOrderPrice + outbidStep) : highestOrderPrice;
                            double totalReq = req * orderQuantity, bal = getPlayerBalance(client);
                            boolean enough = (bal > 0)
                                    ? ((bal >= totalReq) && (lastFailedOrderPrice <= 0 || bal > lastFailedOrderPrice || totalReq < lastFailedOrderPrice))
                                    : (lastFailedOrderPrice > 0 && totalReq < lastFailedOrderPrice);
                            if (enough) {
                                client.player.closeHandledScreen(); lastFailedOrderPrice = -1;
                                logChat(client, "§a§l[Đã Đủ Tiền] §eĐủ tiền Order giá §b" + formatMoney(req) + "§e!");
                                transitionRandom(BotState.SCAN_AH_OPEN, 22, 36, "§aĐã đủ tiền! Quét đặt Order...");
                                return true;
                            }
                            statusText = "§cThiếu tiền (" + (bal > 0 ? formatMoney(bal) : "?") + "/" + formatMoney(totalReq) + ")";
                            if (++moneyWaitLoops < currentMoneyWaitMaxLoops) {
                                transitionRandom(BotState.WAIT_ENOUGH_MONEY_READ, 9, 16, statusText);
                            } else {
                                moneyWaitLoops = 0; client.player.closeHandledScreen();
                                transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 28, 48, statusText);
                            }
                            return true;
                        }
                    }
                } else {
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 24, 40, "§eMở lại /order ely...");
                    return true;
                }
                if (++guiWaitRetries < 20 + random.nextInt(6)) {
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_READ, 4, 8, "§eĐợi tải giá Order...");
                } else {
                    guiWaitRetries = 0; if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 28, 48, "§eLàm mới /order ely...");
                }
                return true;
            }
            case SCAN_AH_OPEN -> {
                if (!pendingBountyCmd.isEmpty()) {
                    stateAfterBounty = BotState.SCAN_AH_OPEN;
                    transitionRandom(BotState.BOUNTY_SEND_CMD, 8, 15, "§aChuyển lời qua /bounty add...");
                    return true;
                }
                if (hasActiveAhListing) { state = BotState.AH_MONITOR_WAIT; return true; }
                if (hasActiveOrder) { state = BotState.MONITOR_AH_OPEN; return true; }
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, 18, 30, "§eĐóng bảng cũ...");
                    return true;
                }
                if (!sendCommandSafe(client, ahSearchCmd)) {
                    transitionRandom(BotState.SCAN_AH_OPEN, 7, 14, "§eĐợi nhịp lệnh...");
                    return true;
                }
                lowestAhPrice = -1; resetAhPagination(); guiWaitRetries = 0;
                transitionRandom(BotState.SCAN_AH_READ, 10, 17, "§eĐang mở /" + ahSearchCmd + "...");
                return true;
            }
            case SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && isGuiFullySettled(client, s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 14 + random.nextInt(5)) {
                            transitionRandom(BotState.SCAN_AH_READ, 4, 8, "§eĐợi sang Trang 2...");
                            return true;
                        }
                        int validCount = countValidAhElytras(client, s);
                        double pageLow = scanAhLowestPrice(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            int nextSlot = findSlotByName(client, "Next page", slotAhNextPageBtn);
                            if (clickSlotIfOpen(client, nextSlot, SlotActionType.PICKUP)) {
                                page1LowestAh = pageLow; clickedNextPage = true; guiWaitRetries = 0; lastPriceSnapshotHash = -1; guiSettleTicks = 0;
                                transitionHuman05s(BotState.SCAN_AH_READ, "§eTrang 1 ít Ely -> Sang Trang 2...");
                            } else {
                                transitionRandom(BotState.SCAN_AH_READ, 2, 5, statusText);
                            }
                            return true;
                        }
                        lowestAhPrice = combineLowestPrice(page1LowestAh, pageLow);
                        if (lowestAhPrice > 0) {
                            lastReadSyncId = syncId; client.player.closeHandledScreen();
                            transitionRandom(BotState.SCAN_ORDER_OPEN, 20, 34, "§eQua xem /order ely...");
                            return true;
                        }
                    }
                }
                if (++guiWaitRetries < 22 + random.nextInt(6)) {
                    transitionRandom(BotState.SCAN_AH_READ, 4, 8, "§eĐợi đồng bộ giá AH...");
                } else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, 26, 44, "§cMở lại AH...");
                }
                return true;
            }
            case SCAN_ORDER_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_ORDER_OPEN, 18, 30, "§eĐợi đóng AH...");
                    return true;
                }
                if (!sendCommandSafe(client, "order ely")) {
                    transitionRandom(BotState.SCAN_ORDER_OPEN, 7, 14, "§eĐợi nhịp lệnh...");
                    return true;
                }
                highestOrderPrice = -1; resetAhPagination(); guiWaitRetries = 0;
                transitionRandom(BotState.SCAN_ORDER_READ, 10, 17, "§eĐang mở /order ely...");
                return true;
            }
            case SCAN_ORDER_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && title.contains("order") && isGuiFullySettled(client, s) && hasAnyElytraWithPrice(client, s)) {
                        double price = scanOrderHighestPrice(client, s);
                        if (price > 0) {
                            lastReadSyncId = syncId; highestOrderPrice = price;
                            transitionRandom(BotState.EVALUATE_INITIAL_PROFIT, 7, 13, "§eTính tiền lời...");
                            return true;
                        }
                    }
                }
                if (++guiWaitRetries < 23 + random.nextInt(6)) {
                    transitionRandom(BotState.SCAN_ORDER_READ, 4, 8, "§eĐợi đồng bộ giá Order...");
                } else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, 26, 44, "§cQuét lại...");
                }
                return true;
            }
// === HẾT PHẦN 4A ===
                 case EVALUATE_INITIAL_PROFIT -> {
                int retryMin = rollRetryScanMinTicks();
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, retryMin, retryMin + 35, "§cĐợi quét lại...");
                    return true;
                }
                recordPriceSample(lowestAhPrice, highestOrderPrice);
                if (antiVolatileEnabled && isMarketPriceChaotic()) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    volatileTicksRemaining = (volatilePauseSec - 15 + random.nextInt(35)) * 20;
                    state = BotState.PAUSE_VOLATILE_MARKET;
                    return true;
                }
                if (verifyingStabilityAfterPause && recentAhPrices.size() < 2) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, 42, 72, "§eKiểm tra ổn định lần 2...");
                    return true;
                }
                verifyingStabilityAfterPause = false;
                double rawDiff = lowestAhPrice - highestOrderPrice;
                double planned = (autoOutbidEnabled && Math.round(rawDiff) >= Math.round(outbidSpreadThreshold))
                        ? (highestOrderPrice + outbidStep) : highestOrderPrice;
                double totalReq = planned * orderQuantity, bal = getPlayerBalance(client);
                if ((bal > 0 && bal < totalReq) || (lastFailedOrderPrice > 0 && totalReq >= lastFailedOrderPrice && (bal <= 0 || bal <= lastFailedOrderPrice))) {
                    guiWaitRetries = 0; moneyWaitLoops = 0;
                    transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 20, 36, "§cThiếu tiền! Canh Order...");
                    return true;
                }
                double netProfit = calculateNetProfit(planned, lowestAhPrice);
                logChat(client, "§7Order: §e" + formatMoney(highestOrderPrice) + " §7| AH: §a" + formatMoney(lowestAhPrice) + " §7| Đặt: §b" + formatMoney(planned) + " §7(Lời: §a+" + formatMoney(netProfit) + "§7)");
                if (Math.round(netProfit) < Math.round(minProfit) || totalReq > maxCapital) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.SCAN_AH_OPEN, retryMin, retryMin + 40, "§eChưa đủ lời (Đợi ~" + (retryMin / 20) + "s)");
                    return true;
                }
                myOrderPrice = planned; guiWaitRetries = 0; enchantRetryCount = 0;
                transitionHuman05s(BotState.ORDER_CLICK_YOUR_ORDERS, "§bĐặt Order " + formatMoney(myOrderPrice) + "...");
                return true;
            }
            case ORDER_CLICK_YOUR_ORDERS -> {
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    if (sendCommandSafe(client, "order ely")) transitionRandom(BotState.ORDER_CLICK_YOUR_ORDERS, 10, 17, "§bMở lại trang Order...");
                    else transitionRandom(BotState.ORDER_CLICK_YOUR_ORDERS, 7, 13, "§bĐợi mở Order...");
                    return true;
                }
                if (clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP)) {
                    guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_NEW_ORDER, "§bBấm New Order...");
                } else {
                    transitionRandom(BotState.ORDER_CLICK_YOUR_ORDERS, 2, 5, statusText);
                }
                return true;
            }
            case ORDER_CLICK_NEW_ORDER -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("enchant")) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                    if (title.contains("your orders")) {
                        int existingSlot = findActiveElytraOrderSlot(s);
                        if (existingSlot != -1) {
                            ItemStack st = s.getScreenHandler().getSlot(existingSlot).getStack();
                            double p = extractUnitPrice(client, st); if (p > 0) myOrderPrice = p;
                            if (isOrderDeliveredInList(client, st)) {
                                if (clickSlotIfOpen(client, existingSlot, SlotActionType.PICKUP)) {
                                    guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_CLICK_COLLECT_BOX, "§aĐơn cũ xong! Lấy Elytra...");
                                } else transitionRandom(BotState.ORDER_CLICK_NEW_ORDER, 2, 5, statusText);
                                return true;
                            }
                            if (!hasExactOrderEnchants(client, st)) {
                                if (clickSlotIfOpen(client, existingSlot, SlotActionType.PICKUP)) {
                                    guiWaitRetries = 0; transitionHuman05s(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, "§cHủy đơn thiếu Mending...");
                                } else transitionRandom(BotState.ORDER_CLICK_NEW_ORDER, 2, 5, statusText);
                                return true;
                            }
                            hasActiveOrder = true; monitorTicksElapsed = 0; guiWaitRetries = 0;
                            currentRefreshTargetTicks = rollNextRefreshTargetTicks();
                            transitionRandom(BotState.ORDER_POST_CONFIRM_CLOSE, 9, 16, "§eĐóng Order qua canh AH...");
                            return true;
                        }
                        if (clickSlotIfOpen(client, findSlotByName(client, "New Order", slotNewOrderBtn), SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_ITEM_SLOT, "§bBấm Choose Item...");
                        } else {
                            transitionRandom(BotState.ORDER_CLICK_NEW_ORDER, 2, 5, statusText);
                        }
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_CLICK_NEW_ORDER, 4, 8, "§bĐợi bảng Your Orders...");
                else transitionHuman05s(BotState.ORDER_CLICK_YOUR_ORDERS, "§cThử lại Your Orders...");
                return true;
            }
            case ORDER_CLICK_ITEM_SLOT -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("enchant")) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                    if (title.contains("new order")) {
                        ItemStack cur = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                        if (isStandaloneElytra(cur) && hasExactOrderEnchants(client, cur)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bĐã đủ Enchant! Nhập giá...");
                            return true;
                        }
                        if (clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_SEARCH_SIGN, "§bBấm Search...");
                        } else transitionRandom(BotState.ORDER_CLICK_ITEM_SLOT, 2, 5, statusText);
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_CLICK_ITEM_SLOT, 4, 8, "§bĐợi bảng New Order...");
                else transitionHuman05s(BotState.ORDER_CLICK_NEW_ORDER, "§cThử lại New Order...");
                return true;
            }
            case ORDER_CLICK_SEARCH_SIGN -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("enchant")) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                    if (title.contains("select item")) {
                        if (findActiveElytraOrderSlot(s) != -1) {
                            transitionHuman05s(BotState.ORDER_SELECT_ELYTRA, "§bĐã thấy Elytra!");
                            return true;
                        }
                        if (clickSlotIfOpen(client, findSlotByName(client, "Search", slotSearchSignBtn), SlotActionType.PICKUP)) {
                            prepareSignSubmission("ely", BotState.ORDER_SELECT_ELYTRA);
                        } else transitionRandom(BotState.ORDER_CLICK_SEARCH_SIGN, 2, 5, statusText);
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_CLICK_SEARCH_SIGN, 4, 8, "§bĐợi bảng Select Item...");
                else transitionHuman05s(BotState.ORDER_CLICK_ITEM_SLOT, "§cBấm lại Choose Item...");
                return true;
            }
// === HẾT PHẦN 4B ===
                    case ORDER_SELECT_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("select item")) {
                        int elySlot = findActiveElytraOrderSlot(s);
                        if (elySlot != -1) {
                            if (clickSlotIfOpen(client, elySlot, SlotActionType.PICKUP)) {
                                guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§bMở bảng Pick Enchantments...");
                            } else transitionRandom(BotState.ORDER_SELECT_ELYTRA, 2, 5, statusText);
                            return true;
                        }
                    } else if (title.contains("enchant")) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_SELECT_ELYTRA, 4, 8, "§bĐợi lọc ra Elytra...");
                else { guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_SEARCH_SIGN, "§eGõ lại chữ ely..."); }
                return true;
            }
            case ORDER_ENCH_UNBREAKING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    int unbSlot = findSlotByName(client, "Unbreaking III", slotEnchantUnbreaking);
                    if (!s.getScreenHandler().getSlot(unbSlot).getStack().isEmpty()) {
                        if (clickSlotIfOpen(client, unbSlot, SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_MENDING, "§bChọn Mending...");
                        } else transitionRandom(BotState.ORDER_ENCH_UNBREAKING, 2, 5, statusText);
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_ENCH_UNBREAKING, 4, 8, "§bĐợi bảng Pick Enchantments...");
                else transitionHuman05s(BotState.ORDER_SELECT_ELYTRA, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_ENCH_MENDING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    int mendSlot = findSlotByName(client, "Mending", slotEnchantMending);
                    if (!s.getScreenHandler().getSlot(mendSlot).getStack().isEmpty()) {
                        if (clickSlotIfOpen(client, mendSlot, SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_ENCH_CONFIRM, "§bLưu cả 2 Enchant...");
                        } else transitionRandom(BotState.ORDER_ENCH_MENDING, 2, 5, statusText);
                        return true;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_ENCH_MENDING, 4, 8, "§bĐợi chọn Mending...");
                else transitionHuman05s(BotState.ORDER_ENCH_UNBREAKING, "§cThử lại Enchant...");
                return true;
            }
            case ORDER_ENCH_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    if (clickSlotIfOpen(client, slotConfirmEnchant, SlotActionType.PICKUP)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bKiểm tra đủ 2 Enchant...");
                    } else transitionRandom(BotState.ORDER_ENCH_CONFIRM, 2, 5, statusText);
                    return true;
                }
                guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bKiểm tra đủ 2 Enchant...");
                return true;
            }
            case ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (isStandaloneElytra(chosen)) {
                        if (hasExactOrderEnchants(client, chosen)) {
                            if (clickSlotIfOpen(client, slotSetPriceBtn, SlotActionType.PICKUP)) {
                                enchantRetryCount = 0;
                                prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.ORDER_CHECK_AMOUNT);
                            } else transitionRandom(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 2, 5, statusText);
                            return true;
                        }
                        if (++enchantRetryCount <= 3) {
                            if (clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP)) {
                                guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_SEARCH_SIGN, "§eChọn lại đủ 2 Enchant...");
                            } else transitionRandom(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 2, 5, statusText);
                            return true;
                        }
                    }
                    guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_ITEM_SLOT, "§cBắt buộc chọn Elytra...");
                    return true;
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 4, 8, "§bĐợi bảng New Order...");
                else transitionHuman05s(BotState.ORDER_CLICK_ITEM_SLOT, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_CHECK_AMOUNT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    guiWaitRetries = 0;
                    if (needsAmountInput(client)) {
                        if (clickSlotIfOpen(client, slotSetAmountBtn, SlotActionType.PICKUP)) {
                            prepareSignSubmission(String.valueOf(orderQuantity), BotState.ORDER_FINAL_CONFIRM);
                        } else transitionRandom(BotState.ORDER_CHECK_AMOUNT, 2, 5, statusText);
                    } else transitionHuman05s(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                    return true;
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_CHECK_AMOUNT, 4, 8, "§bĐợi bảng New Order...");
                else transitionHuman05s(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                return true;
            }
            case ORDER_FINAL_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (!isStandaloneElytra(chosen) || !hasExactOrderEnchants(client, chosen)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.ORDER_CLICK_ITEM_SLOT, "§cThiếu Enchant -> Chọn lại...");
                        return true;
                    }
                    if (clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotConfirmNewOrder), SlotActionType.PICKUP)) {
                        hasActiveOrder = true; chatOrderCompletedFlag = false; monitorTicksElapsed = 0; guiWaitRetries = 0;
                        currentRefreshTargetTicks = rollNextRefreshTargetTicks();
                        transitionRandom(BotState.ORDER_POST_CONFIRM_CLOSE, 9, 16, "§eKiểm tra xác nhận Order...");
                    } else transitionRandom(BotState.ORDER_FINAL_CONFIRM, 2, 5, statusText);
                    return true;
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.ORDER_FINAL_CONFIRM, 4, 8, "§bĐợi bấm Confirm...");
                else transitionRandom(BotState.ORDER_POST_CONFIRM_CLOSE, 8, 14, "§eQua xem AH...");
                return true;
            }
            case ORDER_POST_CONFIRM_CLOSE -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("new order") || (title.contains("your orders") && findActiveElytraOrderSlot(s) == -1)) {
                        client.player.closeHandledScreen(); hasActiveOrder = false; lastFailedOrderPrice = myOrderPrice;
                        transitionRandom(BotState.WAIT_ENOUGH_MONEY_OPEN, 22, 36, "§cKhông đủ tiền! Canh Order...");
                        return true;
                    }
                    client.player.closeHandledScreen(); transitionRandom(BotState.ORDER_POST_CONFIRM_CLOSE, 8, 14, "§eĐợi đóng bảng Order...");
                    return true;
                }
                guiWaitRetries = 0; lastReadSyncId = -1; transitionRandom(BotState.MONITOR_AH_OPEN, 22, 36, "§eMở bảng AH canh giá...");
                return true;
            }
            default -> { return false; }
        }
    }
// === HẾT PHẦN 4C (NHẮN "TIẾP" ĐỂ LẤY PHẦN 5 -> PHẦN 8) ===
        private void tickMonitorClaimAndSell(MinecraftClient client) {
        switch (state) {
            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    fillAndCloseSign(client, signScreen, pendingSignText);
                    guiWaitRetries = 0; transitionHuman05s(nextStateAfterSign, statusText);
                } else if (++guiWaitRetries < 18 + random.nextInt(5)) {
                    transitionRandom(BotState.SUBMIT_SIGN_GUI, 4, 8, statusText);
                } else transitionHuman05s(nextStateAfterSign, statusText);
            }
            case BOUNTY_SEND_CMD -> {
                if (pendingBountyCmd.isEmpty()) { transitionRandom(stateAfterBounty, 10, 16, "§aTiếp tục chu kỳ..."); return; }
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transitionRandom(BotState.BOUNTY_SEND_CMD, 10, 16, "§eĐóng bảng cũ để /bounty..."); return;
                }
                if (!sendCommandSafe(client, pendingBountyCmd)) {
                    transitionRandom(BotState.BOUNTY_SEND_CMD, 8, 14, "§eĐợi nhịp gửi /bounty..."); return;
                }
                guiWaitRetries = 0;
                transitionHuman05s(BotState.BOUNTY_CLICK_GREEN_CONFIRM, "§aĐợi bảng Confirm Bounty...");
            }
            case BOUNTY_CLICK_GREEN_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("bounty") || title.contains("confirm")) {
                        int greenSlot = findBountyGreenConfirmSlot(s);
                        if (greenSlot != -1 && !s.getScreenHandler().getSlot(greenSlot).getStack().isEmpty()) {
                            if (clickSlotIfOpen(client, greenSlot, SlotActionType.PICKUP)) {
                                logChat(client, "§a§l[Auto Bounty] §eĐã thêm Bounty §b" + formatMoney(autoPayAmount) + " §echo §a" + autoPayTarget + " §e(Ô " + greenSlot + ")");
                                pendingBountyCmd = "";
                                client.player.closeHandledScreen();
                                transitionRandom(stateAfterBounty, 22, 36, "§aĐã Confirm Bounty! Tiếp tục...");
                            } else transitionRandom(BotState.BOUNTY_CLICK_GREEN_CONFIRM, 3, 6, statusText);
                            return;
                        }
                    }
                }
                if (++guiWaitRetries < 20 + random.nextInt(6)) {
                    transitionRandom(BotState.BOUNTY_CLICK_GREEN_CONFIRM, 4, 8, "§aĐợi nút Xanh Confirm Bounty...");
                } else {
                    pendingBountyCmd = "";
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(stateAfterBounty, 22, 36, "§eThoát bảng Bounty -> Tiếp tục...");
                }
            }
            case MONITOR_WAIT -> {
                if (monitorTicksElapsed >= currentRefreshTargetTicks) {
                    transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, "§6Đủ ~" + (currentRefreshTargetTicks / 20) + "s! Đi hủy Order..."); return;
                }
                transitionRandom(BotState.MONITOR_AH_OPEN, 24, 38, "§eMở bảng AH canh giá...");
            }
            case MONITOR_AH_OPEN -> {
                if (monitorTicksElapsed >= currentRefreshTargetTicks) {
                    transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, "§6Đủ ~" + (currentRefreshTargetTicks / 20) + "s! Đi hủy Order..."); return;
                }
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transitionRandom(BotState.MONITOR_AH_OPEN, 24, 38, "§eLàm mới bảng AH..."); return;
                }
                if (!sendCommandSafe(client, ahSearchCmd)) {
                    transitionRandom(BotState.MONITOR_AH_OPEN, 8, 15, "§eĐợi nhịp mở AH an toàn..."); return;
                }
                lowestAhPrice = -1; resetAhPagination(); guiWaitRetries = 0; ahWatchLoops = 0;
                transitionRandom(BotState.MONITOR_AH_READ, 10, 16, "§aĐang mở /" + ahSearchCmd + " canh giá...");
            }
            case MONITOR_AH_READ -> {
                if (monitorTicksElapsed >= currentRefreshTargetTicks) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, "§6Đủ ~" + (currentRefreshTargetTicks / 20) + "s! Đi hủy Order..."); return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("order")) {
                        client.player.closeHandledScreen();
                        transitionRandom(BotState.MONITOR_AH_OPEN, 24, 38, "§eĐóng Order -> Mở AH..."); return;
                    }
                    if (isGuiFullySettled(client, s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 14 + random.nextInt(5)) {
                            transitionRandom(BotState.MONITOR_AH_READ, 4, 8, "§eĐợi sang Trang 2..."); return;
                        }
                        guiWaitRetries = 0;
                        int validOnPage = countValidAhElytras(client, s), lowEq = countAhElytraAtOrBelow(client, s, myOrderPrice);
                        double pageLow = scanAhLowestPrice(client, s);
                        if (title.contains("page 1")) {
                            if (lowEq >= 3) {
                                client.player.closeHandledScreen();
                                transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, "§cCó >=3 Elytra <= Order! Hủy..."); return;
                            }
                            if (enableAhPage2 && !clickedNextPage && validOnPage < minElytraPerPage && hasNextPageButton(s)) {
                                int nextSlot = findSlotByName(client, "Next page", slotAhNextPageBtn);
                                if (clickSlotIfOpen(client, nextSlot, SlotActionType.PICKUP)) {
                                    page1CountAtOrBelow = lowEq; page1LowestAh = pageLow; clickedNextPage = true; lastPriceSnapshotHash = -1; guiSettleTicks = 0;
                                    transitionHuman05s(BotState.MONITOR_AH_READ, "§eTrang 1 ít Ely -> Xem Trang 2...");
                                } else transitionRandom(BotState.MONITOR_AH_READ, 2, 5, statusText);
                                return;
                            }
                        } else {
                            lowEq += page1CountAtOrBelow;
                            if (lowEq >= 3) {
                                client.player.closeHandledScreen();
                                transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, "§cCó >=3 Elytra <= Order! Hủy..."); return;
                            }
                        }
                        int targetSec = currentRefreshTargetTicks / 20;
                        statusText = "§aCanh AH (<=" + formatMoney(myOrderPrice) + ": " + lowEq + "/3) " + (monitorTicksElapsed / 20) + "/" + targetSec + "s";
                        if (++ahWatchLoops < currentAhWatchMaxLoops) {
                            transitionRandom(BotState.MONITOR_AH_READ, 8, 14, statusText);
                        } else {
                            ahWatchLoops = 0; client.player.closeHandledScreen();
                            transitionRandom(BotState.MONITOR_AH_OPEN, 28, 46, statusText);
                        }
                        return;
                    }
                } else { transitionRandom(BotState.MONITOR_AH_OPEN, 26, 40, "§eMở lại bảng AH..."); return; }
                if (++guiWaitRetries < 18 + random.nextInt(6)) {
                    transitionRandom(BotState.MONITOR_AH_READ, 4, 8, "§eĐợi tải bảng AH...");
                } else {
                    guiWaitRetries = 0; if (client.currentScreen != null) client.player.closeHandledScreen();
                    transitionRandom(BotState.MONITOR_AH_OPEN, 28, 46, "§eLàm mới /" + ahSearchCmd + "...");
                }
            }
// === HẾT PHẦN 5A ===
                   case CLAIM_OPEN_ORDER -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.CLAIM_OPEN_ORDER, 20, 32, "§aĐóng bảng cũ..."); return; }
                if (!sendCommandSafe(client, "order")) { transitionRandom(BotState.CLAIM_OPEN_ORDER, 7, 13, "§aĐợi nhịp lệnh..."); return; }
                guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_CLICK_YOUR_ORDERS, "§aBấm Your Orders...");
            }
            case CLAIM_CLICK_YOUR_ORDERS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your orders")) {
                    if (clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, "§aChọn đơn Elytra...");
                    } else transitionRandom(BotState.CLAIM_CLICK_YOUR_ORDERS, 2, 5, statusText);
                    return;
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.CLAIM_CLICK_YOUR_ORDERS, 4, 8, "§aĐợi bảng Orders...");
                else transitionRandom(BotState.CLAIM_OPEN_ORDER, 22, 34, "§aMở lại /order...");
            }
            case CLAIM_CLICK_COMPLETED_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int slot = findActiveElytraOrderSlot(s);
                    if (slot != -1) {
                        double p = extractUnitPrice(client, s.getScreenHandler().getSlot(slot).getStack()); if (p > 0) myOrderPrice = p;
                        if (clickSlotIfOpen(client, slot, SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_CLICK_COLLECT_BOX, "§aBấm Collect...");
                        } else transitionRandom(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, 2, 5, statusText);
                        return;
                    }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, 4, 8, "§aĐợi Your Orders...");
                else transitionRandom(BotState.CLAIM_OPEN_ORDER, 22, 34, "§aThử lại /order...");
            }
            case CLAIM_CLICK_COLLECT_BOX -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    if (clickSlotIfOpen(client, findSlotByName(client, "Collect", slotEditOrderCollectBox), SlotActionType.PICKUP)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, "§aLấy Elytra...");
                    } else transitionRandom(BotState.CLAIM_CLICK_COLLECT_BOX, 2, 5, statusText);
                    return;
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.CLAIM_CLICK_COLLECT_BOX, 4, 8, "§aĐợi Edit Order...");
                else transitionRandom(BotState.CLAIM_OPEN_ORDER, 22, 34, "§aMở lại /order...");
            }
            case CLAIM_TAKE_ELYTRA_TO_HOTBAR -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("collect")) {
                    int slot = findActiveElytraOrderSlot(s);
                    if (slot != -1) {
                        if (clickSlotIfOpen(client, slot, SlotActionType.PICKUP)) {
                            hasActiveOrder = false; guiWaitRetries = 0;
                            transitionRandom(BotState.CLAIM_CLOSE_AND_WAIT_INV, 8, 14, "§aĐóng bảng Order...");
                        } else transitionRandom(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, 2, 5, statusText);
                        return;
                    }
                }
                if (++guiWaitRetries < 14 + random.nextInt(5)) transitionRandom(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, 4, 8, "§aĐợi Collect Items...");
                else { guiWaitRetries = 0; transitionRandom(BotState.CLAIM_CLOSE_AND_WAIT_INV, 8, 14, "§aKiểm tra túi đồ..."); }
            }
            case CLAIM_CLOSE_AND_WAIT_INV -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.CLAIM_CLOSE_AND_WAIT_INV, 8, 14, "§aThoát bảng..."); return; }
                if (findElytraInInventory(client) != -1) { hasActiveOrder = false; transitionRandom(BotState.SELL_SCAN_AH_OPEN, 22, 36, "§dQuét giá AH để bán..."); return; }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.CLAIM_CLOSE_AND_WAIT_INV, 4, 8, "§aĐợi đồng bộ...");
                else transitionRandom(BotState.MONITOR_AH_OPEN, 24, 38, "§aTiếp tục canh đơn...");
            }
            case CANCEL_ORDER_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.CANCEL_ORDER_OPEN, 20, 32, "§cĐóng bảng cũ..."); return; }
                if (!sendCommandSafe(client, "order")) { transitionRandom(BotState.CANCEL_ORDER_OPEN, 7, 13, "§cĐợi nhịp lệnh..."); return; }
                guiWaitRetries = 0; transitionHuman05s(BotState.CANCEL_ORDER_CLICK_YOURS, "§cBấm Your Orders...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    if (clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.CANCEL_ORDER_CLICK_ELYTRA, "§cChọn Elytra cần hủy...");
                    } else transitionRandom(BotState.CANCEL_ORDER_CLICK_YOURS, 2, 5, statusText);
                    return;
                }
                if (++guiWaitRetries < 11 + random.nextInt(4)) transitionRandom(BotState.CANCEL_ORDER_CLICK_YOURS, 4, 8, statusText);
                else transitionRandom(BotState.CANCEL_ORDER_OPEN, 22, 36, statusText);
            }
            case CANCEL_ORDER_CLICK_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) {
                        if (isOrderDeliveredInList(client, s.getScreenHandler().getSlot(mySlot).getStack())) {
                            if (clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP)) {
                                guiWaitRetries = 0; transitionHuman05s(BotState.CLAIM_CLICK_COLLECT_BOX, "§aĐơn đã xong! Lấy Elytra...");
                            } else transitionRandom(BotState.CANCEL_ORDER_CLICK_ELYTRA, 2, 5, statusText);
                            return;
                        }
                        if (clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, "§cBấm Cancel (Ô 14)...");
                        } else transitionRandom(BotState.CANCEL_ORDER_CLICK_ELYTRA, 2, 5, statusText);
                        return;
                    } else if (++guiWaitRetries >= 3 + random.nextInt(3)) {
                        restartCycleSafeHuman(client, "§aĐã hủy xong! Quét lại..."); return;
                    }
                }
                if (++guiWaitRetries < 9 + random.nextInt(4)) transitionRandom(BotState.CANCEL_ORDER_CLICK_ELYTRA, 4, 8, statusText);
                else restartCycleSafeHuman(client, "§aQuét giá lại...");
            }
            case CANCEL_ORDER_CLICK_RED_CANCEL -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    if (clickSlotIfOpen(client, findSlotByName(client, "Cancel", slotEditOrderCancelBtn), SlotActionType.PICKUP)) {
                        guiWaitRetries = 0; transitionHuman05s(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, "§cBấm Confirm hủy (Ô 16)...");
                    } else transitionRandom(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 2, 5, statusText);
                    return;
                }
                if (++guiWaitRetries < 9 + random.nextInt(4)) transitionRandom(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 4, 8, statusText);
                else restartCycleSafeHuman(client, "§aQuét giá lại...");
            }
            case CANCEL_ORDER_CLICK_GREEN_CONFIRM -> {
                if (client.currentScreen == null) { restartCycleSafeHuman(client, "§aĐã hủy Order! Quét lại..."); return; }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("cancel") || title.contains("confirm")) {
                        if (clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotCancelOrderConfirmBtn), SlotActionType.PICKUP)) {
                            restartCycleSafeHuman(client, "§aĐã hủy Order! Quét lại...");
                        } else transitionRandom(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, 2, 5, statusText);
                        return;
                    }
                    if (title.contains("your orders") && findActiveElytraOrderSlot(s) == -1) {
                        restartCycleSafeHuman(client, "§aĐã hủy Order! Quét lại..."); return;
                    }
                }
                if (++guiWaitRetries < 7 + random.nextInt(4)) transitionRandom(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, 4, 8, statusText);
                else restartCycleSafeHuman(client, "§aĐã hủy Order! Quét lại...");
            }
// === HẾT PHẦN 5B ===
                   case SELL_SCAN_AH_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.SELL_SCAN_AH_OPEN, 20, 32, "§dĐóng bảng cũ..."); return; }
                if (!sendCommandSafe(client, ahSearchCmd)) { transitionRandom(BotState.SELL_SCAN_AH_OPEN, 7, 13, "§dĐợi nhịp lệnh..."); return; }
                resetAhPagination(); guiWaitRetries = 0; transitionRandom(BotState.SELL_SCAN_AH_READ, 10, 16, "§dQuét /" + ahSearchCmd + " để bán...");
            }
            case SELL_SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (!title.contains("order") && isGuiFullySettled(client, s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 14 + random.nextInt(5)) { transitionRandom(BotState.SELL_SCAN_AH_READ, 4, 8, "§dĐợi sang Trang 2..."); return; }
                        int validCount = countValidAhElytras(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            int nextSlot = findSlotByName(client, "Next page", slotAhNextPageBtn);
                            if (clickSlotIfOpen(client, nextSlot, SlotActionType.PICKUP)) {
                                page1LowestAh = scanAhLowestPrice(client, s); page1CompetitorsBelowTarget = countCompetitorsBelowTarget(client, s, myOrderPrice);
                                clickedNextPage = true; guiWaitRetries = 0; lastPriceSnapshotHash = -1; guiSettleTicks = 0;
                                transitionHuman05s(BotState.SELL_SCAN_AH_READ, "§dTrang 1 ít Ely -> Xem Trang 2...");
                            } else transitionRandom(BotState.SELL_SCAN_AH_READ, 2, 5, statusText);
                            return;
                        }
                        finalAhSellPrice = determineSmartSellPriceMultiPage(client, s, myOrderPrice);
                        client.player.closeHandledScreen(); transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 10, 16, "§dCầm Elytra..."); return;
                    }
                }
                if (++guiWaitRetries < 20 + random.nextInt(6)) transitionRandom(BotState.SELL_SCAN_AH_READ, 4, 8, "§dĐang tải giá AH...");
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); finalAhSellPrice = calculateCustomSellPrice(myOrderPrice, Math.max(0, minProfit - ahPriceDropTotal)); transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 10, 16, "§dCầm Elytra..."); }
            }
            case SELL_SELECT_HOTBAR_SLOT -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 8, 14, "§dĐóng bảng..."); return; }
                int invSlot = findElytraInInventory(client);
                if (invSlot == -1) { if (hasActiveAhListing) state = BotState.AH_MONITOR_WAIT; else restartCycleSafeHuman(client, "§eKhông thấy Elytra, làm lại..."); return; }
                if (invSlot >= 0 && invSlot <= 8) {
                    client.player.getInventory().selectedSlot = invSlot;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(invSlot));
                } else if (client.interactionManager != null) {
                    int hotbar = findEmptyHotbarSlot(client);
                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, invSlot, hotbar, SlotActionType.SWAP, client.player);
                    client.player.getInventory().selectedSlot = hotbar;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbar));
                }
                transitionRandom(BotState.SELL_SEND_AH_CMD, 10, 16, "§a/ah sell " + formatMoney(finalAhSellPrice));
            }
            case SELL_SEND_AH_CMD -> {
                if (isStandaloneElytra(client.player.getMainHandStack())) {
                    if (!sendCommandSafe(client, "ah sell " + toCommandPrice(finalAhSellPrice))) { transitionRandom(BotState.SELL_SEND_AH_CMD, 8, 14, statusText); return; }
                    guiWaitRetries = 0; transitionHuman05s(BotState.SELL_CONFIRM_AH_GUI, "§aĐợi Confirm AH...");
                } else transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 8, 14, statusText);
            }
            case SELL_CONFIRM_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    if (clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotAhConfirmBtn), SlotActionType.PICKUP)) {
                        client.player.closeHandledScreen(); myListedAhPrice = finalAhSellPrice; hasActiveAhListing = true; forceAhRefresh60s = false;
                        if (ahListingStartMs == 0) {
                            ahListingStartMs = System.currentTimeMillis();
                            currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
                        }
                        monitorTicksElapsed = 0;
                        currentRefreshTargetTicks = rollNextRefreshTargetTicks();
                        nextCheckTickTarget = rollNextYourItemsCheckDeltaTicks();
                        logChat(client, "§dĐã treo AH giá §a" + formatMoney(myListedAhPrice) + "§d! Đang chờ người mua...");
                        transitionRandom(BotState.AH_MONITOR_WAIT, 16, 26, "§dChờ mua AH: " + formatMoney(myListedAhPrice));
                    } else transitionRandom(BotState.SELL_CONFIRM_AH_GUI, 2, 5, statusText);
                    return;
                }
                if (++guiWaitRetries < 18 + random.nextInt(6)) transitionRandom(BotState.SELL_CONFIRM_AH_GUI, 4, 8, "§aĐợi Confirm AH...");
                else transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 10, 16, "§eThử lại /ah sell...");
            }
            case AH_MONITOR_WAIT -> {
                monitorTicksElapsed++;
                if (!pendingBountyCmd.isEmpty() && cmdCooldownTicks == 0) {
                    stateAfterBounty = BotState.AH_MONITOR_WAIT;
                    transitionRandom(BotState.BOUNTY_SEND_CMD, 8, 14, "§aChuyển lời qua /bounty add..."); return;
                }
                if (ahListingStartMs == 0) {
                    ahListingStartMs = System.currentTimeMillis();
                    currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
                }
                long unsoldSec = (System.currentTimeMillis() - ahListingStartMs) / 1000;
                int sec60 = monitorTicksElapsed / 20, targetRefreshSec = currentRefreshTargetTicks / 20;
                double curProfit = (myOrderPrice > 0) ? calculateNetProfit(myOrderPrice, myListedAhPrice) : 0;
                double breakEvenPrice = (myOrderPrice > 0) ? calculateCustomSellPrice(myOrderPrice, 0) : 0;
                boolean canDrop1M = autoDiscountEnabled && myOrderPrice > 0 && Math.round(curProfit) >= 1_000_000 && Math.round(myListedAhPrice - 1_000_000) >= Math.round(breakEvenPrice);
                statusText = canDrop1M
                        ? "§dChờ AH " + formatMoney(myListedAhPrice) + " (Hạ 1M: " + unsoldSec + "/" + currentDiscountWaitTargetSec + "s)"
                        : "§dChờ AH " + formatMoney(myListedAhPrice) + " (" + sec60 + "/" + targetRefreshSec + "s)";
                if (autoDiscountEnabled && unsoldSec >= currentDiscountWaitTargetSec) {
                    ahListingStartMs = System.currentTimeMillis();
                    currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
                    if (canDrop1M) {
                        ahPriceDropTotal += 1_000_000; pendingRelistPrice = Math.max(breakEvenPrice, myListedAhPrice - 1_000_000); forceDiscountRelist = true;
                        transitionRandom(BotState.AH_CHECK_OPEN, 22, 36, "§6Hạ 1M -> " + formatMoney(pendingRelistPrice)); return;
                    }
                }
                if (monitorTicksElapsed >= currentRefreshTargetTicks) {
                    forceAhRefresh60s = true;
                    transitionRandom(BotState.AH_CHECK_OPEN, 22, 36, "§6Đủ ~" + targetRefreshSec + "s! Xem giá & bán lại..."); return;
                }
                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += rollNextYourItemsCheckDeltaTicks();
                    forceAhRefresh60s = false;
                    transitionRandom(BotState.AH_CHECK_OPEN, 22, 36, "§dKiểm tra Your Items...");
                }
            }
            case AH_CHECK_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.AH_CHECK_OPEN, 20, 32, statusText); return; }
                if (!sendCommandSafe(client, ahSearchCmd)) { transitionRandom(BotState.AH_CHECK_OPEN, 8, 14, statusText); return; }
                resetAhPagination(); guiWaitRetries = 0; transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 10, 16, "§dXem giá /" + ahSearchCmd + "...");
            }
            case AH_CHECK_READ_AND_CLICK_YOUR_ITEMS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your items")) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (isGuiFullySettled(client, s)) {
                        if (forceDiscountRelist) {
                            if (clickSlotIfOpen(client, findSlotByName(client, "Your Items", slotAhYourItemsBtn), SlotActionType.PICKUP)) {
                                forceDiscountRelist = false; needAhRelist = true; guiWaitRetries = 0;
                                transitionHuman05s(BotState.AH_YOUR_ITEMS_INSPECT, "§eLấy Elytra hạ giá 1M...");
                            } else transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 2, 5, statusText);
                            return;
                        }
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 14 + random.nextInt(5)) { transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 4, 8, "§dĐợi sang Trang 2..."); return; }
                        int validCount = countValidAhElytras(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            int nextSlot = findSlotByName(client, "Next page", slotAhNextPageBtn);
                            if (clickSlotIfOpen(client, nextSlot, SlotActionType.PICKUP)) {
                                page1LowestAh = scanAhLowestPrice(client, s); page1CompetitorsBelowTarget = countCompetitorsBelowTarget(client, s, myOrderPrice);
                                clickedNextPage = true; guiWaitRetries = 0; lastPriceSnapshotHash = -1; guiSettleTicks = 0;
                                transitionHuman05s(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, "§dTrang 1 ít Ely -> Xem Trang 2...");
                            } else transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 2, 5, statusText);
                            return;
                        }
                        double lowAh = combineLowestPrice(page1LowestAh, scanAhLowestPrice(client, s));
                        double smartPrice = determineSmartSellPriceMultiPage(client, s, myOrderPrice);
                        if (lowAh > 0 && Math.round(lowAh) < Math.round(myListedAhPrice)) { pendingRelistPrice = smartPrice; needAhRelist = true; }
                        else if (forceAhRefresh60s) { pendingRelistPrice = myListedAhPrice; needAhRelist = true; }
                        else needAhRelist = false;
                        if (clickSlotIfOpen(client, findSlotByName(client, "Your Items", slotAhYourItemsBtn), SlotActionType.PICKUP)) {
                            guiWaitRetries = 0; transitionHuman05s(BotState.AH_YOUR_ITEMS_INSPECT, "§eKiểm tra Your Items...");
                        } else transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 2, 5, statusText);
                        return;
                    }
                }
                if (++guiWaitRetries < 20 + random.nextInt(6)) transitionRandom(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 4, 8, statusText);
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
            }
            case AH_YOUR_ITEMS_INSPECT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your items")) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) {
                        if (needAhRelist) {
                            if (clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP)) {
                                finalAhSellPrice = pendingRelistPrice; forceAhRefresh60s = false; guiWaitRetries = 0;
                                transitionRandom(BotState.AH_WAIT_RELIST_INV, 10, 16, "§eĐợi Elytra về ô đồ...");
                            } else transitionRandom(BotState.AH_YOUR_ITEMS_INSPECT, 2, 5, statusText);
                        } else { client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
                        return;
                    }
                    if (++guiWaitRetries >= 4 + random.nextInt(3)) { restartCycleSafeHuman(client, "§aĐã bán xong! Quét lại..."); return; }
                }
                if (++guiWaitRetries < 13 + random.nextInt(5)) transitionRandom(BotState.AH_YOUR_ITEMS_INSPECT, 4, 8, statusText);
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
            }
            case AH_WAIT_RELIST_INV -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transitionRandom(BotState.AH_WAIT_RELIST_INV, 8, 14, "§eĐóng Your Items..."); return; }
                if (findElytraInInventory(client) != -1) { transitionRandom(BotState.SELL_SELECT_HOTBAR_SLOT, 10, 16, "§eBán lại: " + formatMoney(finalAhSellPrice)); return; }
                if (++guiWaitRetries < 11 + random.nextInt(4)) transitionRandom(BotState.AH_WAIT_RELIST_INV, 4, 8, "§eĐợi Elytra về túi...");
                else restartCycleSafeHuman(client, "§aLàm lại...");
            }
            default -> {}
        }
    }
// === HẾT PHẦN 6 ===
           private int findBountyGreenConfirmSlot(HandledScreen<?> s) {
        int limit = Math.min(27, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (st.isOf(Items.LIME_STAINED_GLASS_PANE) || st.isOf(Items.GREEN_STAINED_GLASS_PANE)
                    || st.isOf(Items.LIME_WOOL) || st.isOf(Items.GREEN_WOOL)
                    || st.isOf(Items.LIME_TERRACOTTA) || st.isOf(Items.GREEN_TERRACOTTA)) return i;
            String name = st.getName().getString().toLowerCase();
            if (name.contains("confirm") || name.contains("accept") || name.contains("add") || name.contains("yes")) return i;
        }
        return (slotBountyConfirmBtn < s.getScreenHandler().slots.size()) ? slotBountyConfirmBtn : -1;
    }

    private boolean isGuiFullySettled(MinecraftClient client, HandledScreen<?> screen) {
        int size = screen.getScreenHandler().slots.size();
        boolean navBarReady = (size > 51 && !screen.getScreenHandler().getSlot(51).getStack().isEmpty())
                || (size > 49 && !screen.getScreenHandler().getSlot(49).getStack().isEmpty())
                || (size > 53 && !screen.getScreenHandler().getSlot(53).getStack().isEmpty());
        if (!navBarReady) return false;
        long priceHash = 1469598103934665603L; int pricedItemCount = 0;
        for (int i = 0; i < Math.min(45, size); i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty()) {
                double p = isStandaloneElytra(st) ? extractUnitPrice(client, st) : 0;
                if (p > 0) pricedItemCount++;
                priceHash = (priceHash ^ (i * 31L + Math.round(p))) * 1099511628211L;
            }
        }
        if (pricedItemCount == 0) return false;
        if (priceHash == lastPriceSnapshotHash) guiSettleTicks++;
        else { lastPriceSnapshotHash = priceHash; guiSettleTicks = 1; }
        return guiSettleTicks >= 2;
    }

    private boolean hasNextPageButton(HandledScreen<?> screen) {
        if (screen.getScreenHandler().slots.size() <= slotAhNextPageBtn) return false;
        ItemStack st = screen.getScreenHandler().getSlot(slotAhNextPageBtn).getStack();
        return !st.isEmpty() && (st.getName().getString().toLowerCase().contains("next")
                || st.isOf(Items.ARROW) || st.isOf(Items.SPECTRAL_ARROW) || st.isOf(Items.TIPPED_ARROW));
    }

    private boolean isStandaloneElytra(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.ELYTRA
                || stack.getItem() instanceof BlockItem || stack.get(DataComponentTypes.CONTAINER) != null) return false;
        String n = stack.getName().getString().toLowerCase();
        return !n.contains("shulker") && !n.contains("box");
    }

    private int countValidAhElytras(MinecraftClient client, HandledScreen<?> screen) {
        int c = 0, limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(client, st) && extractUnitPrice(client, st) > 0) c++;
        }
        return c;
    }

    private double combineLowestPrice(double p1, double p2) { return (p1 > 0 && p2 > 0) ? Math.min(p1, p2) : ((p1 > 0) ? p1 : p2); }

    public void saveConfig(MinecraftClient client) {
        try {
            File f = new File(client.runDirectory, "config/elyflip_config.dat"); f.getParentFile().mkdirs();
            Files.write(f.toPath(), Arrays.asList(
                    "SHOW_HUD:" + showHud, "NO_GFX:" + noGraphicsMode, "ANTIAFK_ON:" + antiAfkEnabled,
                    "AUTOLOG_PLAYER:" + autoLogOnPlayer, "MIN_PROFIT:" + minProfit, "AUTOPAY_ON:" + autoPayEnabled,
                    "AUTOPAY_TARGET:" + autoPayTarget, "AUTOPAY_THRESHOLD:" + autoPayThreshold,
                    "AUTOPAY_AMOUNT:" + autoPayAmount, "OUTBID_ON:" + autoOutbidEnabled,
                    "PAGE2_ON:" + enableAhPage2, "DISCOUNT_ON:" + autoDiscountEnabled,
                    "DISCOUNT_SEC:" + discountWaitSec, "REFRESH_SEC:" + refreshIntervalSec,
                    "MIN_TICK:" + minRandomTick, "MAX_TICK:" + maxRandomTick
            ), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public void loadConfig(MinecraftClient client) {
        try {
            File f = new File(client.runDirectory, "config/elyflip_config.dat"); if (!f.exists()) return;
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (l.startsWith("SHOW_HUD:")) showHud = Boolean.parseBoolean(l.substring(9).trim());
                else if (l.startsWith("NO_GFX:")) noGraphicsMode = Boolean.parseBoolean(l.substring(7).trim());
                else if (l.startsWith("ANTIAFK_ON:")) antiAfkEnabled = Boolean.parseBoolean(l.substring(11).trim());
                else if (l.startsWith("AUTOLOG_PLAYER:")) autoLogOnPlayer = Boolean.parseBoolean(l.substring(15).trim());
                else if (l.startsWith("MIN_PROFIT:")) minProfit = Double.parseDouble(l.substring(11).trim());
                else if (l.startsWith("AUTOPAY_ON:")) autoPayEnabled = Boolean.parseBoolean(l.substring(11).trim());
                else if (l.startsWith("AUTOPAY_TARGET:")) autoPayTarget = l.substring(15).trim();
                else if (l.startsWith("AUTOPAY_THRESHOLD:")) autoPayThreshold = Double.parseDouble(l.substring(18).trim());
                else if (l.startsWith("AUTOPAY_AMOUNT:")) autoPayAmount = Double.parseDouble(l.substring(15).trim());
                else if (l.startsWith("OUTBID_ON:")) autoOutbidEnabled = Boolean.parseBoolean(l.substring(10).trim());
                else if (l.startsWith("PAGE2_ON:")) enableAhPage2 = Boolean.parseBoolean(l.substring(9).trim());
                else if (l.startsWith("DISCOUNT_ON:")) autoDiscountEnabled = Boolean.parseBoolean(l.substring(12).trim());
                else if (l.startsWith("DISCOUNT_SEC:")) discountWaitSec = Integer.parseInt(l.substring(13).trim());
                else if (l.startsWith("REFRESH_SEC:")) refreshIntervalSec = Integer.parseInt(l.substring(12).trim());
                else if (l.startsWith("MIN_TICK:")) minRandomTick = Math.max(7, Integer.parseInt(l.substring(9).trim()));
                else if (l.startsWith("MAX_TICK:")) maxRandomTick = Math.max(minRandomTick + 4, Integer.parseInt(l.substring(9).trim()));
            }
        } catch (Exception ignored) {}
    }
// === HẾT PHẦN 7 ===
          private double getPlayerBalance(MinecraftClient client) {
        if (client.world == null) return -1;
        try {
            Scoreboard sb = client.world.getScoreboard();
            for (Team team : sb.getTeams()) {
                double p = parseFirstPriceOnLine((team.getPrefix().getString() + " " + team.getSuffix().getString()).replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
                if (p > 0) return p;
                for (String m : team.getPlayerList()) {
                    double pm = parseFirstPriceOnLine(Team.decorateName(team, Text.literal(m)).getString().replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
                    if (pm > 0) return pm;
                }
            }
            for (ScoreboardObjective obj : sb.getObjectives()) {
                for (Method m : Scoreboard.class.getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ScoreboardObjective.class && Collection.class.isAssignableFrom(m.getReturnType())) {
                        Collection<?> entries = (Collection<?>) m.invoke(sb, obj); if (entries == null) continue;
                        for (Object e : entries) {
                            if (e == null) continue;
                            for (Method em : e.getClass().getMethods()) {
                                if (em.getParameterCount() == 0 && (em.getReturnType() == Text.class || em.getReturnType() == String.class)) {
                                    Object v = em.invoke(e);
                                    if (v != null) { double pe = parseFirstPriceOnLine(((v instanceof Text t) ? t.getString() : v.toString()).replaceAll("§[0-9a-fk-orA-FK-OR]", "")); if (pe > 0) return pe; }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void recordPriceSample(double ah, double ord) {
        if (ah > 0) { recentAhPrices.add(ah); if (recentAhPrices.size() > 4) recentAhPrices.remove(0); }
        if (ord > 0) { recentOrderPrices.add(ord); if (recentOrderPrices.size() > 4) recentOrderPrices.remove(0); }
    }

    private boolean isMarketPriceChaotic() { return isSeriesChaotic(recentAhPrices) || isSeriesChaotic(recentOrderPrices); }

    private boolean isSeriesChaotic(List<Double> s) {
        if (s.size() < 3) return false;
        int j = 0; double minP = s.get(0), maxP = s.get(0);
        for (int i = 1; i < s.size(); i++) {
            double p = s.get(i); if (Math.abs(p - s.get(i - 1)) >= 8_000_000) j++;
            if (p < minP) minP = p; if (p > maxP) maxP = p;
        }
        return j >= 2 || ((maxP - minP) >= 20_000_000 && j >= 1);
    }

    private int countAhElytraAtOrBelow(MinecraftClient c, HandledScreen<?> s, double targetOrder) {
        int count = 0, limit = Math.min(45, s.getScreenHandler().slots.size()); long target = Math.round(targetOrder);
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(c, st)) { double p = extractUnitPrice(c, st); if (p > 0 && Math.round(p) <= target) count++; }
        }
        return count;
    }

    private int countCompetitorsBelowTarget(MinecraftClient c, HandledScreen<?> s, double orderPrice) {
        double base = orderPrice > 0 ? orderPrice : (highestOrderPrice > 0 ? highestOrderPrice : 500_000_000);
        double targetMin = calculateCustomSellPrice(base, Math.max(0, minProfit - ahPriceDropTotal));
        int comp = 0, limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(c, st)) { double p = extractUnitPrice(c, st); if (p > 0 && p < targetMin) comp++; }
        }
        return comp;
    }

    private double determineSmartSellPriceMultiPage(MinecraftClient c, HandledScreen<?> s, double orderPrice) {
        if (state == BotState.SELL_SCAN_AH_READ) {
            ahListingStartMs = System.currentTimeMillis();
            currentDiscountWaitTargetSec = rollNextDiscountWaitSec();
            ahPriceDropTotal = 0; forceDiscountRelist = false;
        }
        double base = orderPrice > 0 ? orderPrice : (highestOrderPrice > 0 ? highestOrderPrice : 500_000_000);
        double effProfit = Math.max(0, minProfit - ahPriceDropTotal), targetMin = calculateCustomSellPrice(base, effProfit), breakEven = calculateCustomSellPrice(base, 0);
        double safeFloor = Math.max(breakEven, calculateCustomSellPrice(base, Math.max(0, effProfit - stopLossReduction)));
        double lowAh = combineLowestPrice(page1LowestAh, scanAhLowestPrice(c, s));
        int comp = page1CompetitorsBelowTarget + countCompetitorsBelowTarget(c, s, orderPrice);
        double chosen = (lowAh >= targetMin) ? lowAh : ((lowAh > 0 && comp >= stopLossCompetitorCount) ? Math.max(lowAh, safeFloor) : targetMin);
        if (ahPriceDropTotal > 0) chosen = Math.min(chosen, targetMin);
        return Math.max(breakEven, chosen);
    }

    private void recordAhSaleFromChat(MinecraftClient client, double actualSoldPrice) {
        double base = myOrderPrice > 0 ? myOrderPrice : (highestOrderPrice > 0 ? highestOrderPrice : actualSoldPrice - minProfit);
        soldCount++; lastProfit = calculateNetProfit(base, actualSoldPrice); totalProfit += lastProfit; unpaidProfitPool += Math.max(0, lastProfit);
        hasActiveOrder = false; hasActiveAhListing = false; ahListingStartMs = 0; ahPriceDropTotal = 0; forceDiscountRelist = false;
        logChat(client, "§a§l[CÓ NGƯỜI MUA AH] §eOrder: " + formatMoney(base) + " §7-> §aBán: " + formatMoney(actualSoldPrice) + " §7| Lời: §b+" + formatMoney(lastProfit));
        if (autoPayEnabled && !autoPayTarget.isBlank() && autoPayThreshold > 0 && autoPayAmount > 0 && unpaidProfitPool >= autoPayThreshold) {
            unpaidProfitPool -= autoPayThreshold;
            pendingBountyCmd = "bounty add " + autoPayTarget.trim() + " " + toCommandPrice(autoPayAmount);
        }
    }

    private double parseAmountWithSuffix(String numPart, String suffixPart) {
        try {
            double v = Double.parseDouble(numPart.replace(",", ""));
            if (suffixPart != null) switch (suffixPart.toUpperCase()) { case "K" -> v *= 1_000; case "M" -> v *= 1_000_000; case "B" -> v *= 1_000_000_000; }
            return Math.round(v);
        } catch (NumberFormatException e) { return -1; }
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        if (noGraphicsMode && running && client.currentScreen == null) {
            ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), 0xFF05070A);
        }
        if (!showHud) return;
        ctx.fill(6, 6, 268, 100, 0xCC0B0F17); ctx.fill(6, 6, 268, 8, running ? 0xFF00E676 : 0xFFFF5252); ctx.fill(6, 99, 268, 100, 0xFF00E5FF);
        double avg = soldCount > 0 ? (totalProfit / soldCount) : 0;
        String afkBadge = noGraphicsMode ? " §a[ZeroBlock+Rnd]" : " §a[FullRandom]";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§6§l⚡ AUTO FLIP ELYTRA" + afkBadge), 12, 12, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7Trạng thái: " + statusText), 12, 24, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fĐã bán: §b§l" + soldCount + " cái §7| Gần nhất: §a+" + formatMoney(lastProfit)), 12, 36, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTổng lời: §e§l+" + formatMoney(totalProfit) + " §7(TB: " + formatMoney(avg) + ")"), 12, 48, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTiền lời / 1h: §d§l+" + formatMoney(getProfitPerHour()) + "/h"), 12, 60, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7TG: §f" + getActiveTimeFormatted() + " §7| Đợi Bounty: §a" + formatMoney(unpaidProfitPool) + "/" + formatMoney(autoPayThreshold)), 12, 72, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§8[F6] Bật/Tắt Bot  •  [F9] Bảng Settings"), 12, 85, 0xAAAAAA);
    }
// === HẾT PHẦN 8A ===
         private int findEmptyHotbarSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getStack(i).isEmpty()) return i;
        return 8;
    }

    private boolean hasAnyElytraWithPrice(MinecraftClient c, HandledScreen<?> s) {
        int limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && extractUnitPrice(c, st) > 0) return true;
        }
        return false;
    }

    private List<String> getServerLoreLinesOnly(MinecraftClient client, ItemStack stack) {
        List<String> res = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) res.add(t.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
        try {
            Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
            List<Text> tip = stack.getTooltip(ctx, client.player, TooltipType.BASIC);
            for (int i = 1; i < tip.size(); i++) {
                String line = tip.get(i).getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
                if (!line.isEmpty() && !res.contains(line)) res.add(line);
            }
        } catch (Exception ignored) {}
        return res;
    }

    private boolean isOrderDeliveredInList(MinecraftClient c, ItemStack st) {
        for (String line : getServerLoreLinesOnly(c, st)) {
            String p = line.toLowerCase();
            if (p.contains("order completed") || p.contains("1/1 delivered")) return true;
        }
        return false;
    }

    private double getProfitPerHour() {
        long ms = accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0);
        return (ms < 5000 || totalProfit <= 0) ? 0 : totalProfit / (ms / 3_600_000.0);
    }

    private String getActiveTimeFormatted() {
        long s = (accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0)) / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void prepareSignSubmission(String txt, BotState next) {
        this.pendingSignText = txt; this.nextStateAfterSign = next;
        this.guiWaitRetries = 0; this.state = BotState.SUBMIT_SIGN_GUI;
        this.waitTicks = 8 + random.nextInt(7);
    }

    private void fillAndCloseSign(MinecraftClient client, AbstractSignEditScreen signScreen, String line1) {
        try {
            SignBlockEntity signEntity = null; String[] messages = null; boolean front = true;
            for (Field f : AbstractSignEditScreen.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (SignBlockEntity.class.isAssignableFrom(f.getType())) signEntity = (SignBlockEntity) f.get(signScreen);
                else if (f.getType() == String[].class) messages = (String[]) f.get(signScreen);
                else if (f.getType() == boolean.class) front = f.getBoolean(signScreen);
            }
            if (messages != null && messages.length > 0) messages[0] = line1;
            if (client.getNetworkHandler() != null && signEntity != null) {
                String l2 = (messages != null && messages.length > 1 && messages[1] != null) ? messages[1] : "";
                String l3 = (messages != null && messages.length > 2 && messages[2] != null) ? messages[2] : "";
                String l4 = (messages != null && messages.length > 3 && messages[3] != null) ? messages[3] : "";
                client.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(signEntity.getPos(), front, line1, l2, l3, l4));
            }
            signScreen.close();
        } catch (Exception e) { signScreen.close(); }
    }

    private boolean hasExactOrderEnchants(MinecraftClient client, ItemStack stack) {
        boolean hasUnb3 = false, hasMend = false;
        ItemEnchantmentsComponent enchComp = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchComp != null) {
            for (RegistryEntry<Enchantment> entry : enchComp.getEnchantments()) {
                String id = entry.getIdAsString().toLowerCase(); int lvl = enchComp.getLevel(entry);
                if (id.contains("curse") || id.contains("vanishing") || id.contains("binding")) return false;
                if (id.contains("unbreaking")) { if (lvl == 3) hasUnb3 = true; else return false; }
                if (id.contains("mending") && lvl >= 1) hasMend = true;
            }
        }
        ItemEnchantmentsComponent storedComp = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (storedComp != null) {
            for (RegistryEntry<Enchantment> entry : storedComp.getEnchantments()) {
                String id = entry.getIdAsString().toLowerCase(); int lvl = storedComp.getLevel(entry);
                if (id.contains("unbreaking") && lvl == 3) hasUnb3 = true;
                if (id.contains("mending") && lvl >= 1) hasMend = true;
            }
        }
        for (String line : getServerLoreLinesOnly(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("curse") || plain.contains("vanishing") || plain.contains("binding")) return false;
            if (plain.matches(".*unbreaking\\s+(iii|3)\\b.*") || plain.contains("chậm hỏng iii")) hasUnb3 = true;
            else if (plain.matches(".*unbreaking\\s+(i|ii|1|2)\\b.*")) return false;
            if (plain.contains("mending") || plain.contains("sửa chữa")) hasMend = true;
        }
        return hasUnb3 && hasMend;
    }

    private double scanAhLowestPrice(MinecraftClient c, HandledScreen<?> s) {
        double low = -1; int limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(c, st)) {
                double p = extractUnitPrice(c, st);
                if (p > 0 && (low < 0 || p < low)) low = p;
            }
        }
        return low;
    }

    private double scanOrderHighestPrice(MinecraftClient c, HandledScreen<?> s) {
        double highEnch = -1, highAny = -1; int limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st)) {
                double p = extractUnitPrice(c, st);
                if (p >= 1_000_000 && p <= 50_000_000_000.0) {
                    if (p > highAny) highAny = p;
                    if (hasExactOrderEnchants(c, st) && p > highEnch) highEnch = p;
                }
            }
        }
        return (highEnch > 0 && highAny > 0 && highEnch < highAny * 0.7) ? highAny : (highEnch > 0 ? highEnch : highAny);
    }

    private double extractUnitPrice(MinecraftClient client, ItemStack stack) {
        if (!isStandaloneElytra(stack)) return -1;
        List<String> lines = getServerLoreLinesOnly(client, stack);
        for (String line : lines) {
            if (line.contains("/$")) continue;
            Matcher em = EACH_PRICE_PATTERN.matcher(line);
            if (em.find()) { double p = parseAmountWithSuffix(em.group(1), em.group(2)); if (p >= 100_000) return p; }
            Matcher pm = EXPLICIT_PRICE_PATTERN.matcher(line);
            if (pm.find()) { double p = parseAmountWithSuffix(pm.group(1), pm.group(2)); if (p >= 100_000) return p; }
        }
        for (String line : lines) {
            String plain = line.toLowerCase();
            if (plain.contains("/") || plain.contains("paid") || plain.contains("delivered")
                    || plain.contains("fulfilled") || plain.contains("requested")
                    || plain.contains("seller") || plain.contains("buyer")
                    || plain.contains("expire") || plain.contains("total")) continue;
            double p = parseFirstPriceOnLine(plain);
            if (p >= 100_000) return p;
        }
        return -1;
    }

    private double parseFirstPriceOnLine(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw);
        return m.find() ? parseAmountWithSuffix(m.group(1), m.group(2)) : -1;
    }
// === HẾT PHẦN 8B ===
          private boolean needsAmountInput(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> s) || slotSetAmountBtn >= s.getScreenHandler().slots.size()) return false;
        ItemStack stack = s.getScreenHandler().getSlot(slotSetAmountBtn).getStack();
        if (stack.isEmpty()) return false;
        for (String line : getServerLoreLinesOnly(client, stack)) if (line.contains("(0)")) return true;
        return false;
    }

    private int findSlotByName(MinecraftClient client, String keyword, int fallbackSlot) {
        if (!(client.currentScreen instanceof HandledScreen<?> s)) return fallbackSlot;
        int limit = Math.min(54, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.getName().getString().toLowerCase().contains(keyword.toLowerCase())) return i;
        }
        return fallbackSlot;
    }

    private int findActiveElytraOrderSlot(HandledScreen<?> s) {
        int limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) if (isStandaloneElytra(s.getScreenHandler().getSlot(i).getStack())) return i;
        return -1;
    }

    private double calculateCustomSellPrice(double orderPrice, double targetProfit) { return Math.ceil((orderPrice + targetProfit) / (1.0 - (ahTaxPercent / 100.0))); }
    private double calculateNetProfit(double orderPrice, double ahSellPrice) { return Math.round((ahSellPrice * (1.0 - (ahTaxPercent / 100.0))) - orderPrice); }

    private int findElytraInInventory(MinecraftClient client) {
        for (int i = 0; i < 36; i++) if (isStandaloneElytra(client.player.getInventory().getStack(i))) return i;
        return -1;
    }

    private void simulateMouseMoveToSlot(MinecraftClient client, HandledScreen<?> screen, int slotId) {
        try {
            if (slotId < 0 || slotId >= screen.getScreenHandler().slots.size()) return;
            Slot slot = screen.getScreenHandler().getSlot(slotId);
            double targetGuiX = (screen.width - 176) / 2.0 + slot.x + 3.5 + random.nextDouble() * 9.0;
            double targetGuiY = (screen.height - 222) / 2.0 + slot.y + 3.5 + random.nextDouble() * 9.0;
            double scaleX = (double) client.getWindow().getWidth() / client.getWindow().getScaledWidth();
            double scaleY = (double) client.getWindow().getHeight() / client.getWindow().getScaledHeight();
            GLFW.glfwSetCursorPos(client.getWindow().getHandle(), targetGuiX * scaleX, targetGuiY * scaleY);
        } catch (Exception ignored) {}
    }

    private boolean clickSlotIfOpen(MinecraftClient client, int slotId, SlotActionType type) {
        long now = System.currentTimeMillis();
        if (now - lastClickTimeMs < nextAllowedClickDelayMs) return false;
        if (slotId >= 0 && client.currentScreen instanceof HandledScreen<?> s
                && slotId < s.getScreenHandler().slots.size() && client.interactionManager != null) {
            simulateMouseMoveToSlot(client, s, slotId);
            lastClickTimeMs = now;
            long nextDelay;
            do {
                nextDelay = 370 + random.nextInt(280);
            } while (Math.abs(nextDelay - lastUsedDelayMs) < 18);
            lastUsedDelayMs = nextDelay;
            nextAllowedClickDelayMs = nextDelay;
            client.interactionManager.clickSlot(s.getScreenHandler().syncId, slotId, 0, type, client.player);
            return true;
        }
        return false;
    }

    private boolean sendCommandSafe(MinecraftClient client, String cmd) {
        if (cmdCooldownTicks > 0) return false;
        if (client.player != null && client.player.networkHandler != null) {
            releaseAllAfkKeys(client);
            client.player.networkHandler.sendChatCommand(cmd);
            cmdCooldownTicks = 25 + random.nextInt(20);
            return true;
        }
        return false;
    }

    private void transitionHuman05s(BotState next, String status) {
        int low = Math.max(7, minRandomTick), high = Math.max(low + 4, maxRandomTick);
        int jitter = random.nextInt(4);
        transitionRandom(next, low + jitter, high + jitter + random.nextInt(3), status);
    }

    private void transitionRandom(BotState next, int min, int max, String status) {
        this.state = next;
        int span = Math.max(2, max - min + 1);
        this.waitTicks = min + random.nextInt(span) + (random.nextBoolean() ? random.nextInt(3) : 0);
        this.statusText = status;
    }

    private String formatMoney(double a) {
        if (a >= 1_000_000_000) return String.format("%.2fB", a / 1_000_000_000);
        if (a >= 1_000_000) return String.format("%.2fM", a / 1_000_000);
        if (a >= 1_000) return String.format("%.1fK", a / 1_000);
        return String.valueOf(Math.round(a));
    }

    private String toCommandPrice(double a) { long r = Math.round(a); return (r % 1_000_000 == 0) ? (r / 1_000_000) + "m" : String.valueOf(r); }
    private void logChat(MinecraftClient client, String msg) { if (client.player != null) client.player.sendMessage(Text.literal("§6§l[ElytraFlip] §r" + msg), false); }

    public static class FlipConfigScreen extends Screen {
        private final ElytraFlipClient mod;
        private TextFieldWidget targetField, thresholdField, payAmountField, minProfitField, discountSecField, refreshField, tickField;

        public FlipConfigScreen(ElytraFlipClient mod) { super(Text.literal("Elytra Flip Dashboard")); this.mod = mod; }
        private String btnTxt(String label, boolean on) { return (on ? "§a● " : "§c○ ") + "§f" + label + ": " + (on ? "§a§lBẬT" : "§c§lTẮT"); }
        @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

        @Override
        protected void init() {
            int cx = this.width / 2, py = (this.height - 242) / 2, leftX = cx - 170, rightFieldX = cx + 92, rowY = py + 56;
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Chạy Bot (F6)", mod.running)), b -> { mod.toggleBotRunning(MinecraftClient.getInstance()); b.setMessage(Text.literal(btnTxt("Chạy Bot (F6)", mod.running))); }).dimensions(leftX, rowY, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Anti-AFK (Nhảy)", antiAfkEnabled)), b -> { antiAfkEnabled = !antiAfkEnabled; b.setMessage(Text.literal(btnTxt("Anti-AFK (Nhảy)", antiAfkEnabled))); }).dimensions(leftX, rowY + 18, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Auto Log Gặp Người", autoLogOnPlayer)), b -> { autoLogOnPlayer = !autoLogOnPlayer; b.setMessage(Text.literal(btnTxt("Auto Log Gặp Người", autoLogOnPlayer))); }).dimensions(leftX, rowY + 36, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tắt Hết Block (Mượt)", noGraphicsMode)), b -> {
                noGraphicsMode = !noGraphicsMode;
                if (!noGraphicsMode) mod.restoreWorldBlocks(MinecraftClient.getInstance());
                b.setMessage(Text.literal(btnTxt("Tắt Hết Block (Mượt)", noGraphicsMode)));
            }).dimensions(leftX, rowY + 54, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Đủ Lời Add Bounty", autoPayEnabled)), b -> { autoPayEnabled = !autoPayEnabled; b.setMessage(Text.literal(btnTxt("Đủ Lời Add Bounty", autoPayEnabled))); }).dimensions(leftX, rowY + 72, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tự Hạ 1M (2p30s)", autoDiscountEnabled)), b -> { autoDiscountEnabled = !autoDiscountEnabled; b.setMessage(Text.literal(btnTxt("Tự Hạ 1M (2p30s)", autoDiscountEnabled))); }).dimensions(leftX, rowY + 90, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Đè Giá Order (+1M)", autoOutbidEnabled)), b -> { autoOutbidEnabled = !autoOutbidEnabled; b.setMessage(Text.literal(btnTxt("Đè Giá Order (+1M)", autoOutbidEnabled))); }).dimensions(leftX, rowY + 108, 162, 17).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tự Lật Trang 2 AH", enableAhPage2)), b -> { enableAhPage2 = !enableAhPage2; b.setMessage(Text.literal(btnTxt("Tự Lật Trang 2 AH", enableAhPage2))); }).dimensions(leftX, rowY + 126, 162, 17).build());

            int rY = py + 58;
            targetField = new TextFieldWidget(this.textRenderer, rightFieldX, rY, 78, 17, Text.literal("")); targetField.setMaxLength(32); targetField.setText(autoPayTarget); this.addDrawableChild(targetField);
            thresholdField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 21, 78, 17, Text.literal("")); thresholdField.setText(String.valueOf(autoPayThreshold / 1_000_000.0)); this.addDrawableChild(thresholdField);
            payAmountField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 42, 78, 17, Text.literal("")); payAmountField.setText(String.valueOf(autoPayAmount / 1_000_000.0)); this.addDrawableChild(payAmountField);
            minProfitField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 63, 78, 17, Text.literal("")); minProfitField.setText(String.valueOf(minProfit / 1_000_000.0)); this.addDrawableChild(minProfitField);
            discountSecField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 84, 78, 17, Text.literal("")); discountSecField.setText(String.valueOf(discountWaitSec)); this.addDrawableChild(discountSecField);
            refreshField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 105, 78, 17, Text.literal("")); refreshField.setText(String.valueOf(refreshIntervalSec)); this.addDrawableChild(refreshField);
            tickField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 126, 78, 17, Text.literal("")); tickField.setText(String.valueOf(minRandomTick)); this.addDrawableChild(tickField);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("§e⟳ Reset Thống Kê Về 0"), b -> { mod.soldCount = 0; mod.lastProfit = 0; mod.totalProfit = 0; mod.unpaidProfitPool = 0; mod.accumulatedActiveMs = 0; mod.lastResumeTimeMs = System.currentTimeMillis(); }).dimensions(leftX, py + 214, 162, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("§a§l✔ LƯU CÀI ĐẶT & ĐÓNG"), b -> this.close()).dimensions(cx + 8, py + 214, 162, 20).build());
        }

        private void applyValues() {
            autoPayTarget = targetField.getText().trim();
            try { autoPayThreshold = Math.max(0.1, Double.parseDouble(thresholdField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { autoPayAmount = Math.max(0.1, Double.parseDouble(payAmountField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { minProfit = Math.max(0.1, Double.parseDouble(minProfitField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { discountWaitSec = Math.max(15, Integer.parseInt(discountSecField.getText().trim())); } catch (Exception ignored) {}
            try { refreshIntervalSec = Math.max(20, Integer.parseInt(refreshField.getText().trim())); } catch (Exception ignored) {}
            try { minRandomTick = Math.max(7, Integer.parseInt(tickField.getText().trim())); maxRandomTick = minRandomTick + 5; } catch (Exception ignored) {}
            mod.currentRefreshTargetTicks = mod.rollNextRefreshTargetTicks();
            mod.currentDiscountWaitTargetSec = mod.rollNextDiscountWaitSec();
            mod.saveConfig(MinecraftClient.getInstance());
        }

        @Override public void close() { applyValues(); super.close(); }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ctx.fill(0, 0, this.width, this.height, 0xB005070B);
            int cx = this.width / 2, px = cx - 180, py = (this.height - 242) / 2, pw = 360, ph = 242;
            ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF00E5FF); ctx.fill(px, py, px + pw, py + ph, 0xF50B0F17);
            ctx.fill(px, py, px + pw, py + 40, 0xFF121824); ctx.fill(px, py + 40, px + pw, py + 41, 0xFFFFAA00);
            ctx.fill(cx - 174, py + 45, cx - 4, py + 210, 0x99161F2E); ctx.fill(cx + 4, py + 45, cx + 174, py + 210, 0x99161F2E);

            int rowY = py + 63, labelX = cx + 10;
            String[] labels = {"§fTên Add Bounty:", "§fĐủ lời Add (M):", "§fNhập tiền Add (M):", "§fLời tối thiểu (M):", "§fChờ hạ 1M (Giây):", "§fHủy làm mới (s):", "§fRandom Delay (Tick):"};
            for (int i = 0; i < labels.length; i++) { int ry = rowY + i * 21; ctx.fill(cx + 8, ry - 4, cx + 170, ry + 13, 0x88090D14); }
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§6§l✦ DONUT ELYTRA FLIP §a§lZERO-BLOCK + FULL RANDOM §7(F9) ✦"), cx, py + 6, 0xFFFFFF);
            String sCard = "§7Đã bán: §b§l" + mod.soldCount + "  §8|  §7Tổng lời: §e§l+" + mod.formatMoney(mod.totalProfit) + "  §8|  §7Tốc độ: §d§l+" + mod.formatMoney(mod.getProfitPerHour()) + "/h  §8|  §7Đợi Bounty: §a§l" + mod.formatMoney(mod.unpaidProfitPool);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sCard), cx, py + 22, 0xFFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("§b§l⚙ CÔNG TẮC TÍNH NĂNG"), cx - 168, py + 46, 0xFFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("§e§l💸 ĐỦ LỜI ADD BOUNTY"), cx + 10, py + 46, 0xFFFFFF);
            for (int i = 0; i < labels.length; i++) ctx.drawTextWithShadow(this.textRenderer, Text.literal(labels[i]), labelX, rowY + i * 21, 0xFFFFFF);
        }
    }
}
// === HOÀN TẤT 100% ===
                                 
