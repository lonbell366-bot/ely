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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {
    public static String ahSearchCmd = "ah elytra mending";
    public static double minProfit = 2_000_000, stopLossReduction = 1_000_000;
    public static double outbidSpreadThreshold = 3_000_000, outbidStep = 1_000_000;
    public static double maxCapital = 100_000_000_000.0, ahTaxPercent = 0.0;
    public static int stopLossCompetitorCount = 3, minElytraPerPage = 3;
    public static int orderQuantity = 1, ahYourItemsCheckSec = 25;
    public static int refreshIntervalSec = 60, discountWaitSec = 150, retryScanSec = 6, volatilePauseSec = 300;
    public static int minRandomTick = 6, maxRandomTick = 9;

    // Các công tắc trong bảng Settings F9
    public static boolean showHud = true;
    public static boolean noGraphicsMode = true;
    public static boolean autoLogOnPlayer = false;
    public static boolean autoPayEnabled = false;
    public static boolean autoOutbidEnabled = true;
    public static boolean enableAhPage2 = true;
    public static boolean autoDiscountEnabled = true; // Tự giảm 1M sau 2p30s (150s)
    public static boolean antiVolatileEnabled = true;

    public static String autoPayTarget = "";
    public static double autoPayThreshold = 10_000_000;
    public static double autoPayAmount = 10_000_000;
    private double unpaidProfitPool = 0;
    private String pendingPayCommand = "";

    public static int slotYourOrdersBtn = 51, slotNewOrderBtn = 9, slotChooseItemBtn = 12;
    public static int slotSearchSignBtn = 50, slotSelectElytra = 0, slotEnchantUnbreaking = 36;
    public static int slotEnchantMending = 19, slotConfirmEnchant = 52, slotSetAmountBtn = 13;
    public static int slotSetPriceBtn = 14, slotConfirmNewOrder = 16;
    public static int slotEditOrderCollectBox = 13, slotEditOrderCancelBtn = 14, slotCancelOrderConfirmBtn = 16;
    public static int slotAhYourItemsBtn = 51, slotAhConfirmBtn = 11, slotAhNextPageBtn = 53;

    private static KeyBinding toggleKey, menuKey;
    private boolean configLoaded = false, clickedNextPage = false, forceDiscountRelist = false;
    private boolean running = false, hasActiveOrder = false, hasActiveAhListing = false;
    private boolean needAhRelist = false, forceAhRefresh60s = false, verifyingStabilityAfterPause = false;
    private boolean chatOrderCompletedFlag = false, chatAhSoldFlag = false, chatNotEnoughMoneyFlag = false;
    private BotState state = BotState.IDLE, nextStateAfterSign = BotState.IDLE;
    private String pendingSignText = "", statusText = "§cĐã Tắt";
    private int waitTicks = 0, guiWaitRetries = 0, ahWatchLoops = 0, moneyWaitLoops = 0, cmdCooldownTicks = 0;
    private int lastReadSyncId = -1, lastSlotSnapshotCount = -1, guiSettleTicks = 0;
    private int monitorTicksElapsed = 0, nextCheckTickTarget = 0, volatileTicksRemaining = 0, soldCount = 0;
    private int page1CountAtOrBelow = 0, page1CompetitorsBelowTarget = 0, enchantRetryCount = 0;
    private long lastClickTimeMs = 0, ahListingStartMs = 0;
    private double ahPriceDropTotal = 0, page1LowestAh = -1, lowestAhPrice = -1, highestOrderPrice = -1;
    private double myOrderPrice = 0, finalAhSellPrice = 0, myListedAhPrice = 0, pendingRelistPrice = 0;
    private double lastFailedOrderPrice = -1, lastProfit = 0, totalProfit = 0;
    private long accumulatedActiveMs = 0, lastResumeTimeMs = 0, lastSaleRecordedMs = 0;

    private final List<Double> recentAhPrices = new ArrayList<>();
    private final List<Double> recentOrderPrices = new ArrayList<>();
    private final Random random = new Random();
    private static final Pattern EACH_PRICE_PATTERN = Pattern.compile(
            "\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?\\s*(?:each|/item|/ea)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLICIT_PRICE_PATTERN = Pattern.compile(
            "(?:price|giá|cost|buy)\\s*:?\\s*\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?");
    private static final Pattern AH_BOUGHT_CHAT_PATTERN = Pattern.compile(
            "(?:bought|purchased)\\s+your\\s+(?:[0-9]+x?\\s+)?elytra\\s+for\\s*\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?",
            Pattern.CASE_INSENSITIVE
    );

    private enum BotState {
        IDLE, SCAN_AH_OPEN, SCAN_AH_READ, SCAN_ORDER_OPEN, SCAN_ORDER_READ, EVALUATE_INITIAL_PROFIT,
        WAIT_ENOUGH_MONEY_OPEN, WAIT_ENOUGH_MONEY_READ, PAUSE_VOLATILE_MARKET,
        ORDER_CLICK_YOUR_ORDERS, ORDER_CLICK_NEW_ORDER, ORDER_CLICK_ITEM_SLOT,
        ORDER_CLICK_SEARCH_SIGN, ORDER_SELECT_ELYTRA, ORDER_ENCH_UNBREAKING, ORDER_ENCH_MENDING,
        ORDER_ENCH_CONFIRM, ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, ORDER_CHECK_AMOUNT,
        ORDER_FINAL_CONFIRM, ORDER_POST_CONFIRM_CLOSE,
        SUBMIT_SIGN_GUI, MONITOR_WAIT, MONITOR_AH_OPEN, MONITOR_AH_READ,
        CLAIM_OPEN_ORDER, CLAIM_CLICK_YOUR_ORDERS, CLAIM_CLICK_COMPLETED_ELYTRA,
        CLAIM_CLICK_COLLECT_BOX, CLAIM_TAKE_ELYTRA_TO_HOTBAR, CLAIM_CLOSE_AND_WAIT_INV,
        CANCEL_ORDER_OPEN, CANCEL_ORDER_CLICK_YOURS, CANCEL_ORDER_CLICK_ELYTRA,
        CANCEL_ORDER_CLICK_RED_CANCEL, CANCEL_ORDER_CLICK_GREEN_CONFIRM,
        SELL_SCAN_AH_OPEN, SELL_SCAN_AH_READ, SELL_SELECT_HOTBAR_SLOT, SELL_SEND_AH_CMD, SELL_CONFIRM_AH_GUI,
        AH_MONITOR_WAIT, AH_CHECK_OPEN, AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, AH_YOUR_ITEMS_INSPECT, AH_WAIT_RELIST_INV
    }
// === HẾT PHẦN 1 ===
    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Flip (F6)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "Donut Elytra Flip"
        ));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Mở Bảng Settings (F9)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "Donut Elytra Flip"
        ));
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> onChatMessage(message));
    }

    private void onChatMessage(Text message) {
        if (message == null) return;
        String plain = message.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        String lower = plain.toLowerCase();

        if (running && (lower.contains("your elytra order is complete") || lower.contains("delivered you 1 elytra"))) chatOrderCompletedFlag = true;
        if (running && lower.contains("you listed 1 elytra")) hasActiveAhListing = true;
        if (running && (lower.contains("not enough money") || lower.contains("don't have enough")
                || lower.contains("do not have enough") || lower.contains("insufficient")
                || lower.contains("can't afford") || lower.contains("cannot afford"))) chatNotEnoughMoneyFlag = true;

        Matcher boughtMatcher = AH_BOUGHT_CHAT_PATTERN.matcher(plain);
        if (boughtMatcher.find()) {
            long now = System.currentTimeMillis();
            if (now - lastSaleRecordedMs > 1500) {
                double soldPrice = parseAmountWithSuffix(boughtMatcher.group(1), boughtMatcher.group(2));
                if (soldPrice > 0) {
                    lastSaleRecordedMs = now;
                    recordAhSaleFromChat(MinecraftClient.getInstance(), soldPrice);
                    hasActiveAhListing = false; hasActiveOrder = false; lastFailedOrderPrice = -1; chatAhSoldFlag = true;
                }
            }
        }
    }

    private boolean isSellingOrAhMonitoringState() {
        return state == BotState.SELL_SCAN_AH_OPEN || state == BotState.SELL_SCAN_AH_READ
                || state == BotState.SELL_SELECT_HOTBAR_SLOT || state == BotState.SELL_SEND_AH_CMD
                || state == BotState.SELL_CONFIRM_AH_GUI || state == BotState.CLAIM_CLOSE_AND_WAIT_INV
                || state == BotState.AH_MONITOR_WAIT || state == BotState.AH_CHECK_OPEN
                || state == BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS
                || state == BotState.AH_YOUR_ITEMS_INSPECT || state == BotState.AH_WAIT_RELIST_INV;
    }

    private void resetAhPagination() {
        clickedNextPage = false; page1LowestAh = -1; page1CountAtOrBelow = 0; page1CompetitorsBelowTarget = 0;
        lastSlotSnapshotCount = -1; guiSettleTicks = 0;
    }

    public void toggleBotRunning(MinecraftClient client) {
        running = !running;
        if (running) {
            lastResumeTimeMs = System.currentTimeMillis();
            recentAhPrices.clear(); recentOrderPrices.clear();
            verifyingStabilityAfterPause = false; lastFailedOrderPrice = -1;
            logChat(client, "§aĐã BẬT Auto Flip Elytra! (Bấm §eF9 §ađể mở Settings)");
            restartCycleImmediately(client, "§aĐang khởi động...");
        } else {
            accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs);
            state = BotState.IDLE; statusText = "§cĐã Tắt";
            logChat(client, "§cĐã TẮT Auto Flip Elytra!");
        }
    }

    private void restartCycleImmediately(MinecraftClient client, String reasonMsg) {
        chatAhSoldFlag = false; chatOrderCompletedFlag = false; chatNotEnoughMoneyFlag = false;
        hasActiveOrder = false; hasActiveAhListing = false; forceAhRefresh60s = false;
        needAhRelist = false; forceDiscountRelist = false; ahListingStartMs = 0; ahPriceDropTotal = 0;
        resetAhPagination(); lowestAhPrice = -1; highestOrderPrice = -1; lastReadSyncId = -1; enchantRetryCount = 0;
        guiWaitRetries = 0; ahWatchLoops = 0; moneyWaitLoops = 0; monitorTicksElapsed = 0; cmdCooldownTicks = 0;
        if (client.currentScreen != null && !(client.currentScreen instanceof FlipConfigScreen) && client.player != null) {
            client.player.closeHandledScreen();
        }
        transition(BotState.SCAN_AH_OPEN, 1, 2, reasonMsg);
    }

    private boolean checkAutoLogOnPlayer(MinecraftClient client) {
        if (!autoLogOnPlayer || client.world == null || client.player == null) return false;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == null || other == client.player || other.getUuid().equals(client.player.getUuid())) continue;
            String otherName = other.getName().getString().trim();
            if (otherName.isEmpty()) continue;
            if (!autoPayTarget.isBlank() && otherName.equalsIgnoreCase(autoPayTarget.trim())) continue;
            if (client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerListEntry(other.getUuid()) == null) continue;

            int dist = (int) Math.round(client.player.distanceTo(other));
            if (running) {
                accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs);
                running = false;
            }
            state = BotState.IDLE;
            statusText = "§cAuto Log (Gặp " + otherName + ")";
            if (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection() != null) {
                client.getNetworkHandler().getConnection().disconnect(
                        Text.literal("§6§l⚡ [AUTO LOG BẢO VỆ] ⚡\n\n§fPhát hiện người chơi: §c§l" + otherName + " §7(Cách §e" + dist + "m§7)\n§aĐã tự động thoát khỏi server an toàn!")
                );
            }
            return true;
        }
        return false;
    }

    private void stripWorldGraphicsDirectly(MinecraftClient client) {
        if (!noGraphicsMode || client.world == null) return;
        try {
            client.world.setRainGradient(0.0f);
            client.world.setThunderGradient(0.0f);
            if (client.particleManager != null) {
                for (Field f : ParticleManager.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType()) || Queue.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object obj = f.get(client.particleManager);
                        if (obj instanceof Map<?, ?> m) m.clear();
                        else if (obj instanceof Collection<?> c) c.clear();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (!configLoaded) { configLoaded = true; loadConfig(client); }
        if (cmdCooldownTicks > 0) cmdCooldownTicks--;

        stripWorldGraphicsDirectly(client);
        if (running && checkAutoLogOnPlayer(client)) return;

        while (menuKey.wasPressed()) {
            if (client.currentScreen instanceof FlipConfigScreen s) s.close();
            else client.setScreen(new FlipConfigScreen(this));
            return;
        }

        while (toggleKey.wasPressed()) {
            if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT)) {
                soldCount = 0; lastProfit = 0; totalProfit = 0; unpaidProfitPool = 0;
                accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
            }
            toggleBotRunning(client);
        }

        if (!pendingPayCommand.isEmpty() && cmdCooldownTicks == 0) {
            if (sendCommandSafe(client, pendingPayCommand)) {
                logChat(client, "§a§l[Auto Pay] §eĐã tự động chuyển §b" + formatMoney(autoPayAmount) + " §echo §a" + autoPayTarget + "§e!");
                pendingPayCommand = "";
            }
        }

        if (!running || state == BotState.IDLE || client.currentScreen instanceof FlipConfigScreen) return;

        if (chatNotEnoughMoneyFlag) {
            chatNotEnoughMoneyFlag = false; hasActiveOrder = false;
            if (myOrderPrice > 0) lastFailedOrderPrice = myOrderPrice;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            double bal = getPlayerBalance(client);
            logChat(client, "§c§l[Không Đủ Tiền] §eTiền có: §a" + (bal > 0 ? formatMoney(bal) : "Thiếu") + " §c< Cần: §b" + formatMoney(myOrderPrice) + "§c! Đang canh giá Order...");
            cmdCooldownTicks = 0;
            transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, "§cKhông đủ tiền! Đang canh giá Order...");
            return;
        }
        if (hasActiveOrder && (state == BotState.MONITOR_WAIT || state == BotState.MONITOR_AH_OPEN
                || state == BotState.MONITOR_AH_READ || state == BotState.ORDER_POST_CONFIRM_CLOSE)) monitorTicksElapsed++;

        if (chatAhSoldFlag) { restartCycleImmediately(client, "§aĐã bán xong! Làm lại ngay..."); return; }
        if (!hasActiveAhListing && !isSellingOrAhMonitoringState() && findElytraInInventory(client) != -1) {
            chatOrderCompletedFlag = false; hasActiveOrder = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            cmdCooldownTicks = 0;
            transition(BotState.SELL_SCAN_AH_OPEN, 3, 5, "§dCó Elytra -> Bán AH...");
            return;
        }
        if (chatOrderCompletedFlag && !hasActiveAhListing && !isSellingOrAhMonitoringState()
                && state != BotState.CLAIM_OPEN_ORDER && state != BotState.CLAIM_CLICK_YOUR_ORDERS
                && state != BotState.CLAIM_CLICK_COMPLETED_ELYTRA && state != BotState.CLAIM_CLICK_COLLECT_BOX
                && state != BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR) {
            chatOrderCompletedFlag = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            cmdCooldownTicks = 0;
            transition(BotState.CLAIM_OPEN_ORDER, 3, 5, "§aĐơn xong! Mở /order...");
            return;
        }
        if (waitTicks > 0) { waitTicks--; return; }
        if (!tickScanAndCreateOrder(client)) tickMonitorClaimAndSell(client);
    }
// === HẾT PHẦN 2 ===
    private boolean tickScanAndCreateOrder(MinecraftClient client) {
        switch (state) {
            case PAUSE_VOLATILE_MARKET -> {
                if (client.currentScreen != null) client.player.closeHandledScreen();
                if (volatileTicksRemaining > 0) {
                    volatileTicksRemaining--;
                    statusText = "§cGiá loạn! Chờ ổn định (" + (volatileTicksRemaining / 20) + "/" + volatilePauseSec + "s)";
                    return true;
                }
                recentAhPrices.clear(); recentOrderPrices.clear(); verifyingStabilityAfterPause = true;
                transition(BotState.SCAN_AH_OPEN, 4, 6, "§eKiểm tra lại độ ổn định giá...");
                return true;
            }
            case WAIT_ENOUGH_MONEY_OPEN -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("order") && !title.contains("your") && !title.contains("new") && !title.contains("edit")) {
                        guiWaitRetries = 0; moneyWaitLoops = 0; resetAhPagination();
                        transition(BotState.WAIT_ENOUGH_MONEY_READ, 4, 6, statusText);
                        return true;
                    }
                    client.player.closeHandledScreen();
                    transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, "§cĐóng bảng cũ...");
                    return true;
                }
                if (!sendCommandSafe(client, "order ely")) { transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, statusText); return true; }
                guiWaitRetries = 0; moneyWaitLoops = 0; resetAhPagination();
                transition(BotState.WAIT_ENOUGH_MONEY_READ, 8, 11, "§eĐang xem giá Order chờ đủ tiền...");
                return true;
            }
            case WAIT_ENOUGH_MONEY_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    if (isGuiFullySettled(s) && hasAnyElytraWithPrice(client, s)) {
                        guiWaitRetries = 0;
                        double price = scanOrderHighestPrice(client, s);
                        if (price > 0) {
                            highestOrderPrice = price;
                            double rawDiff = (lowestAhPrice > 0) ? (lowestAhPrice - highestOrderPrice) : 0;
                            double req = (autoOutbidEnabled && lowestAhPrice > 0 && Math.round(rawDiff) >= Math.round(outbidSpreadThreshold))
                                    ? (highestOrderPrice + outbidStep) : highestOrderPrice;
                            double totalReq = req * orderQuantity, bal = getPlayerBalance(client);
                            boolean enough = (bal > 0) ? ((bal >= totalReq) && (lastFailedOrderPrice <= 0 || bal > lastFailedOrderPrice || totalReq < lastFailedOrderPrice))
                                    : (lastFailedOrderPrice > 0 && totalReq < lastFailedOrderPrice);
                            if (enough) {
                                client.player.closeHandledScreen(); lastFailedOrderPrice = -1;
                                logChat(client, "§a§l[Đã Đủ Tiền] §eĐủ tiền Order giá §b" + formatMoney(req) + "§e! Bắt đầu đặt đơn...");
                                cmdCooldownTicks = 0;
                                transition(BotState.SCAN_AH_OPEN, 2, 4, "§aĐã đủ tiền! Quét đặt Order...");
                                return true;
                            }
                            statusText = "§cThiếu tiền (" + (bal > 0 ? formatMoney(bal) : "?") + "/" + formatMoney(totalReq) + ")";
                            if (++moneyWaitLoops < 20) transition(BotState.WAIT_ENOUGH_MONEY_READ, 5, 6, statusText);
                            else { moneyWaitLoops = 0; cmdCooldownTicks = 0; sendCommandSafe(client, "order ely"); transition(BotState.WAIT_ENOUGH_MONEY_READ, 8, 11, statusText); }
                            return true;
                        }
                    }
                } else { cmdCooldownTicks = 0; transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, "§eMở lại /order ely..."); return true; }
                if (++guiWaitRetries < 22) transition(BotState.WAIT_ENOUGH_MONEY_READ, 3, 5, "§eĐợi tải giá Order...");
                else { guiWaitRetries = 0; cmdCooldownTicks = 0; sendCommandSafe(client, "order ely"); transition(BotState.WAIT_ENOUGH_MONEY_READ, 8, 11, "§eLàm mới /order ely..."); }
                return true;
            }
            case SCAN_AH_OPEN -> {
                if (hasActiveAhListing) { state = BotState.AH_MONITOR_WAIT; return true; }
                if (hasActiveOrder) { state = BotState.MONITOR_AH_OPEN; return true; }
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.SCAN_AH_OPEN, 3, 5, "§eĐóng bảng cũ..."); return true; }
                if (!sendCommandSafe(client, ahSearchCmd)) { transition(BotState.SCAN_AH_OPEN, 3, 5, "§eĐợi nhịp lệnh..."); return true; }
                lowestAhPrice = -1; resetAhPagination(); guiWaitRetries = 0;
                transition(BotState.SCAN_AH_READ, 7, 10, "§eĐang mở /" + ahSearchCmd + "...");
                return true;
            }
            case SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && isGuiFullySettled(s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 16) {
                            transition(BotState.SCAN_AH_READ, 3, 4, "§eĐợi sang Trang 2...");
                            return true;
                        }
                        int validCount = countValidAhElytras(client, s);
                        double pageLow = scanAhLowestPrice(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            page1LowestAh = pageLow; clickedNextPage = true; guiWaitRetries = 0;
                            lastSlotSnapshotCount = -1; guiSettleTicks = 0;
                            clickSlotIfOpen(client, findSlotByName(client, "Next page", slotAhNextPageBtn), SlotActionType.PICKUP);
                            transition(BotState.SCAN_AH_READ, 6, 9, "§eTrang 1 ít Ely (" + validCount + ") -> Sang Trang 2...");
                            return true;
                        }
                        lowestAhPrice = combineLowestPrice(page1LowestAh, pageLow);
                        if (lowestAhPrice > 0) {
                            lastReadSyncId = syncId;
                            client.player.closeHandledScreen();
                            cmdCooldownTicks = 0;
                            transition(BotState.SCAN_ORDER_OPEN, 2, 4, "§eQua xem /order ely...");
                            return true;
                        }
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SCAN_AH_READ, 3, 4, "§eĐợi đồng bộ giá AH...");
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); cmdCooldownTicks = 0; transition(BotState.SCAN_AH_OPEN, 6, 10, "§cMở lại AH..."); }
                return true;
            }
            case SCAN_ORDER_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.SCAN_ORDER_OPEN, 3, 5, "§eĐợi đóng AH..."); return true; }
                if (!sendCommandSafe(client, "order ely")) { transition(BotState.SCAN_ORDER_OPEN, 3, 5, "§eĐợi nhịp lệnh..."); return true; }
                highestOrderPrice = -1; resetAhPagination(); guiWaitRetries = 0;
                transition(BotState.SCAN_ORDER_READ, 7, 10, "§eĐang mở /order ely...");
                return true;
            }
            case SCAN_ORDER_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && title.contains("order") && isGuiFullySettled(s) && hasAnyElytraWithPrice(client, s)) {
                        double price = scanOrderHighestPrice(client, s);
                        if (price > 0) {
                            lastReadSyncId = syncId;
                            highestOrderPrice = price;
                            transition(BotState.EVALUATE_INITIAL_PROFIT, 2, 4, "§eTính tiền lời...");
                            return true;
                        }
                    }
                }
                if (++guiWaitRetries < 26) transition(BotState.SCAN_ORDER_READ, 3, 4, "§eĐợi đồng bộ giá Order...");
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); cmdCooldownTicks = 0; transition(BotState.SCAN_AH_OPEN, 6, 10, "§cQuét lại..."); }
                return true;
            }
            case EVALUATE_INITIAL_PROFIT -> {
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§cĐợi quét lại...");
                    return true;
                }
                recordPriceSample(lowestAhPrice, highestOrderPrice);
                if (antiVolatileEnabled && isMarketPriceChaotic()) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    volatileTicksRemaining = volatilePauseSec * 20;
                    logChat(client, "§c§l[Cảnh Báo] Giá nhảy quá lộn xộn! Tạm dừng Order chờ 5 phút...");
                    state = BotState.PAUSE_VOLATILE_MARKET;
                    return true;
                }
                if (verifyingStabilityAfterPause && recentAhPrices.size() < 2) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 60, 80, "§eKiểm tra độ ổn định lần 2...");
                    return true;
                }
                verifyingStabilityAfterPause = false;

                double rawDiff = lowestAhPrice - highestOrderPrice;
                double planned = (autoOutbidEnabled && Math.round(rawDiff) >= Math.round(outbidSpreadThreshold))
                        ? (highestOrderPrice + outbidStep) : highestOrderPrice;
                double totalReq = planned * orderQuantity, bal = getPlayerBalance(client);

                if ((bal > 0 && bal < totalReq) || (lastFailedOrderPrice > 0 && totalReq >= lastFailedOrderPrice && (bal <= 0 || bal <= lastFailedOrderPrice))) {
                    logChat(client, "§c§l[Không Đủ Tiền] §eTiền có: §a" + (bal > 0 ? formatMoney(bal) : "Thiếu") + " §c< Cần: §b" + formatMoney(totalReq) + "§c! Đang canh giá Order...");
                    guiWaitRetries = 0; moneyWaitLoops = 0;
                    transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, "§cThiếu tiền! Đang canh giá Order...");
                    return true;
                }
                double netProfit = calculateNetProfit(planned, lowestAhPrice);
                logChat(client, "§7Order cao nhất: §e" + formatMoney(highestOrderPrice) + " §7| AH thấp nhất: §a" + formatMoney(lowestAhPrice) + " §7| Đặt: §b" + formatMoney(planned) + " §7(Lời: §a+" + formatMoney(netProfit) + "§7)");
                if (Math.round(netProfit) < Math.round(minProfit) || totalReq > maxCapital) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§eChưa đủ lời (Đợi " + retryScanSec + "s)");
                    return true;
                }
                myOrderPrice = planned; guiWaitRetries = 0; enchantRetryCount = 0;
                transition(BotState.ORDER_CLICK_YOUR_ORDERS, 3, 5, "§bĐặt Order " + formatMoney(myOrderPrice) + "...");
                return true;
            }
// === HẾT PHẦN 3 ===
                           case ORDER_CLICK_YOUR_ORDERS -> {
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    if (sendCommandSafe(client, "order ely")) transition(BotState.ORDER_CLICK_YOUR_ORDERS, 6, 9, "§bMở lại trang Order...");
                    else transition(BotState.ORDER_CLICK_YOUR_ORDERS, 4, 6, "§bĐợi mở Order...");
                    return true;
                }
                clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                guiWaitRetries = 0; transition(BotState.ORDER_CLICK_NEW_ORDER, 4, 6, "§bBấm New Order...");
                return true;
            }
            case ORDER_CLICK_NEW_ORDER -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int existingSlot = findActiveElytraOrderSlot(s);
                    if (existingSlot != -1) {
                        ItemStack st = s.getScreenHandler().getSlot(existingSlot).getStack();
                        double p = extractUnitPrice(client, st);
                        if (p > 0) myOrderPrice = p;
                        if (isOrderDeliveredInList(client, st)) {
                            clickSlotIfOpen(client, existingSlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0; transition(BotState.CLAIM_CLICK_COLLECT_BOX, 4, 6, "§aĐơn cũ đã xong! Lấy Elytra...");
                            return true;
                        }
                        if (!hasExactOrderEnchants(client, st)) {
                            logChat(client, "§c[Bảo Vệ] Phát hiện đơn cũ thiếu Mending! Đang hủy ngay...");
                            clickSlotIfOpen(client, existingSlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0; transition(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 4, 6, "§cHủy đơn thiếu Mending...");
                            return true;
                        }
                        hasActiveOrder = true; monitorTicksElapsed = 0; guiWaitRetries = 0;
                        transition(BotState.ORDER_POST_CONFIRM_CLOSE, 4, 6, "§eĐóng Order qua canh AH...");
                        return true;
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "New Order", slotNewOrderBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§bBấm Choose Item...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_NEW_ORDER, 3, 4, "§bĐợi bảng Your Orders...");
                else transition(BotState.ORDER_CLICK_YOUR_ORDERS, 4, 6, "§cThử lại Your Orders...");
                return true;
            }
            case ORDER_CLICK_ITEM_SLOT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack cur = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (isStandaloneElytra(cur) && hasExactOrderEnchants(client, cur)) {
                        guiWaitRetries = 0; transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 3, 5, "§bĐã đủ Enchant! Nhập giá...");
                        return true;
                    }
                    clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.ORDER_CLICK_SEARCH_SIGN, 4, 6, "§bBấm Search...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_ITEM_SLOT, 3, 4, "§bĐợi bảng New Order...");
                else transition(BotState.ORDER_CLICK_NEW_ORDER, 4, 6, "§cThử lại New Order...");
                return true;
            }
            case ORDER_CLICK_SEARCH_SIGN -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("select item")) {
                    if (findActiveElytraOrderSlot(s) != -1) { transition(BotState.ORDER_SELECT_ELYTRA, 2, 4, "§bĐã thấy Elytra!"); return true; }
                    clickSlotIfOpen(client, findSlotByName(client, "Search", slotSearchSignBtn), SlotActionType.PICKUP);
                    prepareSignSubmission("ely", BotState.ORDER_SELECT_ELYTRA);
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_SEARCH_SIGN, 3, 4, "§bĐợi bảng Select Item...");
                else transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§cBấm lại Choose Item...");
                return true;
            }
            case ORDER_SELECT_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("select item")) {
                        int elySlot = findActiveElytraOrderSlot(s);
                        if (elySlot != -1) {
                            clickSlotIfOpen(client, elySlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0; transitionFastAntiSus(BotState.ORDER_ENCH_UNBREAKING, "§bMở bảng Enchant...");
                            return true;
                        }
                    } else if (title.contains("enchant")) {
                        guiWaitRetries = 0; transitionFastAntiSus(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_SELECT_ELYTRA, 3, 4, "§bĐợi lọc ra Elytra...");
                else { guiWaitRetries = 0; transition(BotState.ORDER_CLICK_SEARCH_SIGN, 4, 6, "§eGõ lại chữ ely..."); }
                return true;
            }
            case ORDER_ENCH_UNBREAKING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    if (!s.getScreenHandler().getSlot(slotEnchantUnbreaking).getStack().isEmpty()) {
                        clickSlotIfOpen(client, slotEnchantUnbreaking, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionFastAntiSus(BotState.ORDER_ENCH_MENDING, "§bChọn Mending...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_ENCH_UNBREAKING, 2, 4, "§bĐợi tải ô Enchant...");
                else transition(BotState.ORDER_SELECT_ELYTRA, 4, 6, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_ENCH_MENDING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    if (!s.getScreenHandler().getSlot(slotEnchantMending).getStack().isEmpty()) {
                        clickSlotIfOpen(client, slotEnchantMending, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionFastAntiSus(BotState.ORDER_ENCH_CONFIRM, "§bLưu cả 2 Enchant...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_ENCH_MENDING, 2, 4, "§bĐợi chọn Mending...");
                else transition(BotState.ORDER_ENCH_UNBREAKING, 3, 5, "§cThử lại Enchant...");
                return true;
            }
            case ORDER_ENCH_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    clickSlotIfOpen(client, slotConfirmEnchant, SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 4, 6, "§bKiểm tra đủ 2 Enchant...");
                    return true;
                }
                guiWaitRetries = 0;
                transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 4, 6, "§bKiểm tra đủ 2 Enchant...");
                return true;
            }
            case ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (isStandaloneElytra(chosen)) {
                        if (hasExactOrderEnchants(client, chosen)) {
                            enchantRetryCount = 0;
                            clickSlotIfOpen(client, slotSetPriceBtn, SlotActionType.PICKUP);
                            prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.ORDER_CHECK_AMOUNT);
                            return true;
                        }
                        if (++enchantRetryCount <= 3) {
                            logChat(client, "§e[Kiểm Tra] Elytra chưa nhận đủ Mending + Unbreaking III -> Chọn lại Enchant...");
                            clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP);
                            guiWaitRetries = 0;
                            transition(BotState.ORDER_CLICK_SEARCH_SIGN, 4, 6, "§eChọn lại đủ 2 Enchant...");
                            return true;
                        }
                    }
                    guiWaitRetries = 0; transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§cBắt buộc chọn Elytra...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 3, 4, "§bĐợi bảng New Order...");
                else transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_CHECK_AMOUNT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    guiWaitRetries = 0;
                    if (needsAmountInput(client)) {
                        clickSlotIfOpen(client, slotSetAmountBtn, SlotActionType.PICKUP);
                        prepareSignSubmission(String.valueOf(orderQuantity), BotState.ORDER_FINAL_CONFIRM);
                    } else transitionFastAntiSus(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CHECK_AMOUNT, 3, 4, "§bĐợi bảng New Order...");
                else transitionFastAntiSus(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                return true;
            }
            case ORDER_FINAL_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (!isStandaloneElytra(chosen) || !hasExactOrderEnchants(client, chosen)) {
                        guiWaitRetries = 0; transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§cThiếu Enchant -> Chọn lại...");
                        return true;
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotConfirmNewOrder), SlotActionType.PICKUP);
                    hasActiveOrder = true; chatOrderCompletedFlag = false; monitorTicksElapsed = 0; guiWaitRetries = 0;
                    transition(BotState.ORDER_POST_CONFIRM_CLOSE, 6, 9, "§eKiểm tra xác nhận Order...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_FINAL_CONFIRM, 3, 4, "§bĐợi bấm Confirm...");
                else transition(BotState.ORDER_POST_CONFIRM_CLOSE, 5, 8, "§eQua xem AH ngay...");
                return true;
            }
            case ORDER_POST_CONFIRM_CLOSE -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("new order") || (title.contains("your orders") && findActiveElytraOrderSlot(s) == -1)) {
                        client.player.closeHandledScreen();
                        hasActiveOrder = false; lastFailedOrderPrice = myOrderPrice;
                        logChat(client, "§c§l[Không Đủ Tiền] §eKhông đủ tiền đặt Order §b" + formatMoney(myOrderPrice) + "§e! Chuyển sang canh giá Order...");
                        cmdCooldownTicks = 0;
                        transition(BotState.WAIT_ENOUGH_MONEY_OPEN, 4, 6, "§cKhông đủ tiền! Đang canh giá Order...");
                        return true;
                    }
                    client.player.closeHandledScreen();
                    transition(BotState.ORDER_POST_CONFIRM_CLOSE, 4, 6, "§eĐợi đóng bảng Order...");
                    return true;
                }
                cmdCooldownTicks = 0; guiWaitRetries = 0; lastReadSyncId = -1;
                transition(BotState.MONITOR_AH_OPEN, 2, 4, "§eMở bảng AH canh liên tục...");
                return true;
            }
            default -> { return false; }
        }
    }
// === HẾT PHẦN 4 ===
          private void tickMonitorClaimAndSell(MinecraftClient client) {
        switch (state) {
            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    fillAndCloseSign(client, signScreen, pendingSignText);
                    guiWaitRetries = 0; transition(nextStateAfterSign, 4, 6, statusText);
                } else if (++guiWaitRetries < 20) transition(BotState.SUBMIT_SIGN_GUI, 2, 4, statusText);
                else transition(nextStateAfterSign, 4, 6, statusText);
            }
            case MONITOR_WAIT -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    cmdCooldownTicks = 0;
                    transition(BotState.CANCEL_ORDER_OPEN, 1, 2, "§6Đủ " + refreshIntervalSec + "s! Đi hủy Order...");
                    return;
                }
                cmdCooldownTicks = 0; transition(BotState.MONITOR_AH_OPEN, 2, 4, "§eMở bảng AH canh liên tục...");
            }
            case MONITOR_AH_OPEN -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    cmdCooldownTicks = 0;
                    transition(BotState.CANCEL_ORDER_OPEN, 1, 2, "§6Đủ " + refreshIntervalSec + "s! Đi hủy Order...");
                    return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    if (!s.getTitle().getString().toLowerCase().contains("order")) {
                        guiWaitRetries = 0; ahWatchLoops = 0; resetAhPagination();
                        transition(BotState.MONITOR_AH_READ, 3, 5, "§aĐang giữ bảng AH canh giá...");
                        return;
                    }
                    client.player.closeHandledScreen();
                    transition(BotState.MONITOR_AH_OPEN, 3, 5, "§eĐóng bảng Order để mở AH...");
                    return;
                }
                cmdCooldownTicks = 0; sendCommandSafe(client, ahSearchCmd);
                lowestAhPrice = -1; resetAhPagination(); guiWaitRetries = 0; ahWatchLoops = 0;
                transition(BotState.MONITOR_AH_READ, 6, 9, "§aĐang mở /" + ahSearchCmd + " canh giá...");
            }
            case MONITOR_AH_READ -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    cmdCooldownTicks = 0;
                    transition(BotState.CANCEL_ORDER_OPEN, 1, 2, "§6Đủ " + refreshIntervalSec + "s! Đi hủy Order...");
                    return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("order")) {
                        client.player.closeHandledScreen(); cmdCooldownTicks = 0;
                        transition(BotState.MONITOR_AH_OPEN, 4, 6, "§eĐóng bảng Order -> Mở lại AH...");
                        return;
                    }
                    if (isGuiFullySettled(s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 15) {
                            transition(BotState.MONITOR_AH_READ, 3, 4, "§eĐợi sang Trang 2...");
                            return;
                        }
                        guiWaitRetries = 0;
                        int validOnPage = countValidAhElytras(client, s);
                        int lowEq = countAhElytraAtOrBelow(client, s, myOrderPrice);
                        double pageLow = scanAhLowestPrice(client, s);

                        if (title.contains("page 1")) {
                            if (lowEq >= 3) {
                                client.player.closeHandledScreen();
                                logChat(client, "§c[Hủy Khẩn Cấp] Có " + lowEq + " Elytra Trang 1 <= Order! Hủy làm lại ngay!");
                                cmdCooldownTicks = 0;
                                transition(BotState.CANCEL_ORDER_OPEN, 1, 2, "§cCó >=3 Elytra <= Order! Hủy...");
                                return;
                            }
                            if (enableAhPage2 && !clickedNextPage && validOnPage < minElytraPerPage && hasNextPageButton(s)) {
                                page1CountAtOrBelow = lowEq; page1LowestAh = pageLow; clickedNextPage = true;
                                lastSlotSnapshotCount = -1; guiSettleTicks = 0;
                                clickSlotIfOpen(client, findSlotByName(client, "Next page", slotAhNextPageBtn), SlotActionType.PICKUP);
                                transition(BotState.MONITOR_AH_READ, 6, 9, "§eTrang 1 ít Ely -> Xem Trang 2...");
                                return;
                            }
                        } else {
                            lowEq += page1CountAtOrBelow;
                            if (lowEq >= 3) {
                                client.player.closeHandledScreen();
                                logChat(client, "§c[Hủy Khẩn Cấp] Có " + lowEq + " Elytra (Trang 1+2) <= Order! Hủy làm lại ngay!");
                                cmdCooldownTicks = 0;
                                transition(BotState.CANCEL_ORDER_OPEN, 1, 2, "§cCó >=3 Elytra <= Order! Hủy...");
                                return;
                            }
                        }
                        statusText = "§aCanh AH (<=" + formatMoney(myOrderPrice) + ": " + lowEq + "/3) " + (monitorTicksElapsed / 20) + "/" + refreshIntervalSec + "s";
                        if (++ahWatchLoops < 22) transition(BotState.MONITOR_AH_READ, 5, 6, statusText);
                        else {
                            ahWatchLoops = 0; resetAhPagination(); cmdCooldownTicks = 0;
                            sendCommandSafe(client, ahSearchCmd); transition(BotState.MONITOR_AH_READ, 6, 9, statusText);
                        }
                        return;
                    }
                } else { cmdCooldownTicks = 0; transition(BotState.MONITOR_AH_OPEN, 2, 4, "§eMở lại bảng AH ngay..."); return; }
                if (++guiWaitRetries < 20) transition(BotState.MONITOR_AH_READ, 3, 4, "§eĐợi tải bảng AH...");
                else { guiWaitRetries = 0; resetAhPagination(); cmdCooldownTicks = 0; sendCommandSafe(client, ahSearchCmd); transition(BotState.MONITOR_AH_READ, 6, 9, "§eLàm mới /" + ahSearchCmd + "..."); }
            }
            case CLAIM_OPEN_ORDER -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.CLAIM_OPEN_ORDER, 3, 5, "§aĐóng bảng cũ..."); return; }
                cmdCooldownTicks = 0; sendCommandSafe(client, "order"); guiWaitRetries = 0;
                transition(BotState.CLAIM_CLICK_YOUR_ORDERS, 4, 6, "§aBấm Your Orders...");
            }
            case CLAIM_CLICK_YOUR_ORDERS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your orders")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, 4, 6, "§aChọn đơn Elytra...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_YOUR_ORDERS, 3, 4, "§aĐợi bảng Orders...");
                else transition(BotState.CLAIM_OPEN_ORDER, 3, 5, "§aMở lại /order...");
            }
            case CLAIM_CLICK_COMPLETED_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int slot = findActiveElytraOrderSlot(s);
                    if (slot != -1) {
                        double p = extractUnitPrice(client, s.getScreenHandler().getSlot(slot).getStack());
                        if (p > 0) myOrderPrice = p;
                        clickSlotIfOpen(client, slot, SlotActionType.PICKUP);
                        guiWaitRetries = 0; transition(BotState.CLAIM_CLICK_COLLECT_BOX, 4, 6, "§aBấm Collect...");
                        return;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, 3, 4, "§aĐợi Your Orders...");
                else transition(BotState.CLAIM_OPEN_ORDER, 3, 5, "§aThử lại /order...");
            }
            case CLAIM_CLICK_COLLECT_BOX -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Collect", slotEditOrderCollectBox), SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, 4, 6, "§aLấy Elytra...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_COLLECT_BOX, 3, 4, "§aĐợi Edit Order...");
                else transition(BotState.CLAIM_OPEN_ORDER, 3, 5, "§aMở lại /order...");
            }
            case CLAIM_TAKE_ELYTRA_TO_HOTBAR -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("collect")) {
                    int slot = findActiveElytraOrderSlot(s);
                    if (slot != -1) {
                        clickSlotIfOpen(client, slot, SlotActionType.PICKUP);
                        hasActiveOrder = false; guiWaitRetries = 0;
                        transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 4, 6, "§aĐóng bảng Order...");
                        return;
                    }
                }
                if (++guiWaitRetries < 16) transition(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, 3, 4, "§aĐợi Collect Items...");
                else { guiWaitRetries = 0; transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 3, 5, "§aKiểm tra túi đồ..."); }
            }
            case CLAIM_CLOSE_AND_WAIT_INV -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 3, 5, "§aThoát bảng..."); return; }
                if (findElytraInInventory(client) != -1) { hasActiveOrder = false; cmdCooldownTicks = 0; transition(BotState.SELL_SCAN_AH_OPEN, 2, 4, "§dQuét giá AH để bán..."); return; }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 3, 4, "§aĐợi đồng bộ...");
                else transition(BotState.MONITOR_AH_OPEN, 3, 5, "§aTiếp tục canh đơn...");
            }
// === HẾT PHẦN 5 ===
                 case CANCEL_ORDER_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.CANCEL_ORDER_OPEN, 2, 4, "§cĐóng bảng cũ..."); return; }
                cmdCooldownTicks = 0; sendCommandSafe(client, "order"); guiWaitRetries = 0;
                transition(BotState.CANCEL_ORDER_CLICK_YOURS, 4, 6, "§cBấm Your Orders...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.CANCEL_ORDER_CLICK_ELYTRA, 4, 6, "§cChọn Elytra cần hủy...");
                    return;
                }
                if (++guiWaitRetries < 12) transition(BotState.CANCEL_ORDER_CLICK_YOURS, 2, 4, statusText);
                else transition(BotState.CANCEL_ORDER_OPEN, 2, 4, statusText);
            }
            case CANCEL_ORDER_CLICK_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) {
                        if (isOrderDeliveredInList(client, s.getScreenHandler().getSlot(mySlot).getStack())) {
                            clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0; transition(BotState.CLAIM_CLICK_COLLECT_BOX, 4, 6, "§aĐơn đã xong! Lấy Elytra...");
                            return;
                        }
                        clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                        guiWaitRetries = 0; transition(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 4, 6, "§cBấm Cancel (Ô 14)...");
                        return;
                    } else if (++guiWaitRetries >= 3) {
                        restartCycleImmediately(client, "§aĐã hủy xong! Quét giá lại ngay...");
                        return;
                    }
                }
                if (++guiWaitRetries < 10) transition(BotState.CANCEL_ORDER_CLICK_ELYTRA, 2, 3, statusText);
                else restartCycleImmediately(client, "§aQuét giá lại ngay...");
            }
            case CANCEL_ORDER_CLICK_RED_CANCEL -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Cancel", slotEditOrderCancelBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0; transition(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, 3, 5, "§cBấm Confirm hủy (Ô 16)...");
                    return;
                }
                if (++guiWaitRetries < 10) transition(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 2, 3, statusText);
                else restartCycleImmediately(client, "§aQuét giá lại ngay...");
            }
            case CANCEL_ORDER_CLICK_GREEN_CONFIRM -> {
                if (client.currentScreen == null) {
                    restartCycleImmediately(client, "§aĐã hủy Order! Quét giá lại ngay...");
                    return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("cancel") || title.contains("confirm")) {
                        clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotCancelOrderConfirmBtn), SlotActionType.PICKUP);
                        restartCycleImmediately(client, "§aĐã hủy Order! Quét giá lại ngay...");
                        return;
                    }
                    if (title.contains("your orders") && findActiveElytraOrderSlot(s) == -1) {
                        restartCycleImmediately(client, "§aĐã hủy Order! Quét giá lại ngay...");
                        return;
                    }
                }
                if (++guiWaitRetries < 6) transition(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, 2, 3, statusText);
                else restartCycleImmediately(client, "§aĐã hủy Order! Quét giá lại ngay...");
            }
            case SELL_SCAN_AH_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.SELL_SCAN_AH_OPEN, 3, 5, "§dĐóng bảng cũ..."); return; }
                if (!sendCommandSafe(client, ahSearchCmd)) { transition(BotState.SELL_SCAN_AH_OPEN, 3, 5, "§dĐợi nhịp lệnh..."); return; }
                resetAhPagination(); guiWaitRetries = 0; transition(BotState.SELL_SCAN_AH_READ, 7, 10, "§dQuét /" + ahSearchCmd + " để bán...");
            }
            case SELL_SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (!title.contains("order") && isGuiFullySettled(s)) {
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 15) {
                            transition(BotState.SELL_SCAN_AH_READ, 3, 4, "§dĐợi sang Trang 2...");
                            return;
                        }
                        int validCount = countValidAhElytras(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            page1LowestAh = scanAhLowestPrice(client, s);
                            page1CompetitorsBelowTarget = countCompetitorsBelowTarget(client, s, myOrderPrice);
                            clickedNextPage = true; guiWaitRetries = 0;
                            lastSlotSnapshotCount = -1; guiSettleTicks = 0;
                            clickSlotIfOpen(client, findSlotByName(client, "Next page", slotAhNextPageBtn), SlotActionType.PICKUP);
                            transition(BotState.SELL_SCAN_AH_READ, 6, 9, "§dTrang 1 ít Ely -> Xem Trang 2...");
                            return;
                        }
                        finalAhSellPrice = determineSmartSellPriceMultiPage(client, s, myOrderPrice);
                        client.player.closeHandledScreen();
                        transition(BotState.SELL_SELECT_HOTBAR_SLOT, 3, 5, "§dCầm Elytra...");
                        return;
                    }
                }
                if (++guiWaitRetries < 22) transition(BotState.SELL_SCAN_AH_READ, 3, 5, "§dĐang tải giá AH...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    finalAhSellPrice = calculateCustomSellPrice(myOrderPrice, Math.max(0, minProfit - ahPriceDropTotal));
                    transition(BotState.SELL_SELECT_HOTBAR_SLOT, 3, 5, "§dCầm Elytra...");
                }
            }
            case SELL_SELECT_HOTBAR_SLOT -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.SELL_SELECT_HOTBAR_SLOT, 3, 5, "§dĐóng bảng..."); return; }
                int invSlot = findElytraInInventory(client);
                if (invSlot == -1) {
                    if (hasActiveAhListing) state = BotState.AH_MONITOR_WAIT;
                    else restartCycleImmediately(client, "§eKhông thấy Elytra, làm lại ngay...");
                    return;
                }
                if (invSlot >= 0 && invSlot <= 8) {
                    client.player.getInventory().selectedSlot = invSlot;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(invSlot));
                } else if (client.interactionManager != null) {
                    int hotbar = findEmptyHotbarSlot(client);
                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, invSlot, hotbar, SlotActionType.SWAP, client.player);
                    client.player.getInventory().selectedSlot = hotbar;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbar));
                }
                cmdCooldownTicks = 0;
                transition(BotState.SELL_SEND_AH_CMD, 3, 5, "§a/ah sell " + formatMoney(finalAhSellPrice));
            }
            case SELL_SEND_AH_CMD -> {
                if (isStandaloneElytra(client.player.getMainHandStack())) {
                    if (!sendCommandSafe(client, "ah sell " + toCommandPrice(finalAhSellPrice))) { transition(BotState.SELL_SEND_AH_CMD, 3, 5, statusText); return; }
                    guiWaitRetries = 0; transition(BotState.SELL_CONFIRM_AH_GUI, 5, 8, "§aĐợi Confirm AH...");
                } else transition(BotState.SELL_SELECT_HOTBAR_SLOT, 3, 5, statusText);
            }
            case SELL_CONFIRM_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotAhConfirmBtn), SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                    myListedAhPrice = finalAhSellPrice; hasActiveAhListing = true; forceAhRefresh60s = false;
                    if (ahListingStartMs == 0) ahListingStartMs = System.currentTimeMillis();
                    monitorTicksElapsed = 0; nextCheckTickTarget = ahYourItemsCheckSec * 20;
                    logChat(client, "§dĐã treo AH giá §a" + formatMoney(myListedAhPrice) + "§d! Đang chờ người mua...");
                    transition(BotState.AH_MONITOR_WAIT, 10, 15, "§dChờ mua AH: " + formatMoney(myListedAhPrice));
                    return;
                }
                if (++guiWaitRetries < 20) transition(BotState.SELL_CONFIRM_AH_GUI, 3, 4, "§aĐợi Confirm AH...");
                else transition(BotState.SELL_SELECT_HOTBAR_SLOT, 4, 6, "§eThử lại /ah sell...");
            }
            // TỰ ĐỘNG GIẢM 1M SAU 2P30S (150 GIÂY) KHI ĐANG LỜI >= 2M HOẶC >= 1M (LỜI 1M -> HÒA VỐN)
            case AH_MONITOR_WAIT -> {
                monitorTicksElapsed++;
                if (ahListingStartMs == 0) ahListingStartMs = System.currentTimeMillis();
                long unsoldSec = (System.currentTimeMillis() - ahListingStartMs) / 1000;
                int sec60 = monitorTicksElapsed / 20;

                double curProfit = (myOrderPrice > 0) ? calculateNetProfit(myOrderPrice, myListedAhPrice) : 0;
                double breakEvenPrice = (myOrderPrice > 0) ? calculateCustomSellPrice(myOrderPrice, 0) : 0;
                boolean canDrop1M = autoDiscountEnabled && myOrderPrice > 0
                        && Math.round(curProfit) >= 1_000_000
                        && Math.round(myListedAhPrice - 1_000_000) >= Math.round(breakEvenPrice);

                if (canDrop1M) {
                    statusText = "§dChờ AH " + formatMoney(myListedAhPrice) + " (Hạ 1M: " + unsoldSec + "/" + discountWaitSec + "s)";
                } else {
                    statusText = "§dChờ AH " + formatMoney(myListedAhPrice) + " (" + sec60 + "/" + refreshIntervalSec + "s)";
                }

                if (autoDiscountEnabled && unsoldSec >= discountWaitSec) {
                    ahListingStartMs = System.currentTimeMillis();
                    if (canDrop1M) {
                        ahPriceDropTotal += 1_000_000;
                        pendingRelistPrice = Math.max(breakEvenPrice, myListedAhPrice - 1_000_000);
                        forceDiscountRelist = true;
                        double profitLeft = calculateNetProfit(myOrderPrice, pendingRelistPrice);
                        String profitTag = (Math.round(profitLeft) > 0) ? ("Vẫn lời §b+" + formatMoney(profitLeft)) : "§eHòa vốn";
                        logChat(client, "§e§l[Giảm Giá " + discountWaitSec + "s] §fChưa ai mua! Tự giảm §c-1M §f-> Giá mới: §a"
                                + formatMoney(pendingRelistPrice) + " §7(" + profitTag + "§7)");
                        cmdCooldownTicks = 0;
                        transition(BotState.AH_CHECK_OPEN, 2, 4, "§6Hạ 1M -> " + formatMoney(pendingRelistPrice));
                        return;
                    }
                }

                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    forceAhRefresh60s = true; cmdCooldownTicks = 0;
                    transition(BotState.AH_CHECK_OPEN, 2, 4, "§6Đủ " + refreshIntervalSec + "s! Xem giá & bán lại...");
                    return;
                }
                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += ahYourItemsCheckSec * 20;
                    forceAhRefresh60s = false; cmdCooldownTicks = 0;
                    transition(BotState.AH_CHECK_OPEN, 2, 4, "§dKiểm tra Your Items...");
                }
            }
            case AH_CHECK_OPEN -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.AH_CHECK_OPEN, 3, 5, statusText); return; }
                if (!sendCommandSafe(client, ahSearchCmd)) { transition(BotState.AH_CHECK_OPEN, 3, 5, statusText); return; }
                resetAhPagination(); guiWaitRetries = 0; transition(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 7, 10, "§dXem giá /" + ahSearchCmd + "...");
            }
            case AH_CHECK_READ_AND_CLICK_YOUR_ITEMS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your items")) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (isGuiFullySettled(s)) {
                        if (forceDiscountRelist) {
                            forceDiscountRelist = false; needAhRelist = true;
                            clickSlotIfOpen(client, findSlotByName(client, "Your Items", slotAhYourItemsBtn), SlotActionType.PICKUP);
                            guiWaitRetries = 0; transition(BotState.AH_YOUR_ITEMS_INSPECT, 4, 6, "§eLấy Elytra hạ giá 1M...");
                            return;
                        }
                        if (clickedNextPage && title.contains("page 1") && ++guiWaitRetries < 15) {
                            transition(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 3, 4, "§dĐợi sang Trang 2...");
                            return;
                        }
                        int validCount = countValidAhElytras(client, s);
                        if (enableAhPage2 && !clickedNextPage && title.contains("page 1") && validCount < minElytraPerPage && hasNextPageButton(s)) {
                            page1LowestAh = scanAhLowestPrice(client, s);
                            page1CompetitorsBelowTarget = countCompetitorsBelowTarget(client, s, myOrderPrice);
                            clickedNextPage = true; guiWaitRetries = 0;
                            lastSlotSnapshotCount = -1; guiSettleTicks = 0;
                            clickSlotIfOpen(client, findSlotByName(client, "Next page", slotAhNextPageBtn), SlotActionType.PICKUP);
                            transition(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 6, 9, "§dTrang 1 ít Ely -> Xem Trang 2...");
                            return;
                        }
                        double lowAh = combineLowestPrice(page1LowestAh, scanAhLowestPrice(client, s));
                        double smartPrice = determineSmartSellPriceMultiPage(client, s, myOrderPrice);
                        if (lowAh > 0 && Math.round(lowAh) < Math.round(myListedAhPrice)) { pendingRelistPrice = smartPrice; needAhRelist = true; }
                        else if (forceAhRefresh60s) { pendingRelistPrice = myListedAhPrice; needAhRelist = true; }
                        else needAhRelist = false;

                        clickSlotIfOpen(client, findSlotByName(client, "Your Items", slotAhYourItemsBtn), SlotActionType.PICKUP);
                        guiWaitRetries = 0; transition(BotState.AH_YOUR_ITEMS_INSPECT, 4, 6, "§eKiểm tra Your Items...");
                        return;
                    }
                }
                if (++guiWaitRetries < 22) transition(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 3, 5, statusText);
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
            }
            case AH_YOUR_ITEMS_INSPECT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your items")) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) {
                        if (needAhRelist) {
                            clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                            finalAhSellPrice = pendingRelistPrice; forceAhRefresh60s = false; guiWaitRetries = 0;
                            transition(BotState.AH_WAIT_RELIST_INV, 4, 6, "§eĐợi Elytra về ô đồ...");
                        } else { client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
                        return;
                    }
                    if (++guiWaitRetries >= 5) { restartCycleImmediately(client, "§aĐã bán xong! Làm lại ngay..."); return; }
                }
                if (++guiWaitRetries < 15) transition(BotState.AH_YOUR_ITEMS_INSPECT, 3, 4, statusText);
                else { if (client.currentScreen != null) client.player.closeHandledScreen(); state = BotState.AH_MONITOR_WAIT; }
            }
            case AH_WAIT_RELIST_INV -> {
                if (client.currentScreen != null) { client.player.closeHandledScreen(); transition(BotState.AH_WAIT_RELIST_INV, 3, 5, "§eĐóng Your Items..."); return; }
                if (findElytraInInventory(client) != -1) { transition(BotState.SELL_SELECT_HOTBAR_SLOT, 3, 5, "§eBán lại: " + formatMoney(finalAhSellPrice)); return; }
                if (++guiWaitRetries < 12) transition(BotState.AH_WAIT_RELIST_INV, 3, 4, "§eĐợi Elytra về túi...");
                else restartCycleImmediately(client, "§aLàm lại ngay...");
            }
            default -> {}
        }
    }
// === HẾT PHẦN 6 ===
          private boolean isGuiFullySettled(HandledScreen<?> screen) {
        int size = screen.getScreenHandler().slots.size();
        boolean navBarReady = (size > 51 && !screen.getScreenHandler().getSlot(51).getStack().isEmpty())
                || (size > 49 && !screen.getScreenHandler().getSlot(49).getStack().isEmpty())
                || (size > 53 && !screen.getScreenHandler().getSlot(53).getStack().isEmpty());
        if (!navBarReady) return false;
        int itemSlotsCount = 0;
        for (int i = 0; i < Math.min(45, size); i++) {
            if (!screen.getScreenHandler().getSlot(i).getStack().isEmpty()) itemSlotsCount++;
        }
        if (itemSlotsCount == 0) return false;
        if (itemSlotsCount == lastSlotSnapshotCount) guiSettleTicks++;
        else { lastSlotSnapshotCount = itemSlotsCount; guiSettleTicks = 1; }
        return guiSettleTicks >= 2;
    }

    private boolean hasNextPageButton(HandledScreen<?> screen) {
        if (screen.getScreenHandler().slots.size() <= slotAhNextPageBtn) return false;
        ItemStack st = screen.getScreenHandler().getSlot(slotAhNextPageBtn).getStack();
        if (st.isEmpty()) return false;
        return st.getName().getString().toLowerCase().contains("next") || st.isOf(Items.ARROW) || st.isOf(Items.SPECTRAL_ARROW) || st.isOf(Items.TIPPED_ARROW);
    }

    private boolean isStandaloneElytra(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.ELYTRA || stack.getItem() instanceof BlockItem) return false;
        if (stack.get(DataComponentTypes.CONTAINER) != null) return false;
        String itemName = stack.getName().getString().toLowerCase();
        return !itemName.contains("shulker") && !itemName.contains("box");
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
            File f = new File(client.runDirectory, "config/elyflip_config.dat");
            f.getParentFile().mkdirs();
            List<String> lines = Arrays.asList(
                    "SHOW_HUD:" + showHud,
                    "NO_GFX:" + noGraphicsMode,
                    "AUTOLOG_PLAYER:" + autoLogOnPlayer,
                    "MIN_PROFIT:" + minProfit,
                    "AUTOPAY_ON:" + autoPayEnabled,
                    "AUTOPAY_TARGET:" + autoPayTarget,
                    "AUTOPAY_THRESHOLD:" + autoPayThreshold,
                    "AUTOPAY_AMOUNT:" + autoPayAmount,
                    "OUTBID_ON:" + autoOutbidEnabled,
                    "PAGE2_ON:" + enableAhPage2,
                    "DISCOUNT_ON:" + autoDiscountEnabled,
                    "DISCOUNT_SEC:" + discountWaitSec,
                    "REFRESH_SEC:" + refreshIntervalSec,
                    "MIN_TICK:" + minRandomTick,
                    "MAX_TICK:" + maxRandomTick
            );
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public void loadConfig(MinecraftClient client) {
        try {
            File f = new File(client.runDirectory, "config/elyflip_config.dat");
            if (!f.exists()) return;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("SHOW_HUD:")) showHud = Boolean.parseBoolean(line.substring(9).trim());
                else if (line.startsWith("NO_GFX:")) noGraphicsMode = Boolean.parseBoolean(line.substring(7).trim());
                else if (line.startsWith("AUTOLOG_PLAYER:")) autoLogOnPlayer = Boolean.parseBoolean(line.substring(15).trim());
                else if (line.startsWith("MIN_PROFIT:")) minProfit = Double.parseDouble(line.substring(11).trim());
                else if (line.startsWith("AUTOPAY_ON:")) autoPayEnabled = Boolean.parseBoolean(line.substring(11).trim());
                else if (line.startsWith("AUTOPAY_TARGET:")) autoPayTarget = line.substring(15).trim();
                else if (line.startsWith("AUTOPAY_THRESHOLD:")) autoPayThreshold = Double.parseDouble(line.substring(18).trim());
                else if (line.startsWith("AUTOPAY_AMOUNT:")) autoPayAmount = Double.parseDouble(line.substring(15).trim());
                else if (line.startsWith("OUTBID_ON:")) autoOutbidEnabled = Boolean.parseBoolean(line.substring(10).trim());
                else if (line.startsWith("PAGE2_ON:")) enableAhPage2 = Boolean.parseBoolean(line.substring(9).trim());
                else if (line.startsWith("DISCOUNT_ON:")) autoDiscountEnabled = Boolean.parseBoolean(line.substring(12).trim());
                else if (line.startsWith("DISCOUNT_SEC:")) discountWaitSec = Integer.parseInt(line.substring(13).trim());
                else if (line.startsWith("REFRESH_SEC:")) refreshIntervalSec = Integer.parseInt(line.substring(12).trim());
                else if (line.startsWith("MIN_TICK:")) minRandomTick = Integer.parseInt(line.substring(9).trim());
                else if (line.startsWith("MAX_TICK:")) maxRandomTick = Integer.parseInt(line.substring(9).trim());
            }
        } catch (Exception ignored) {}
    }

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
                        Collection<?> entries = (Collection<?>) m.invoke(sb, obj);
                        if (entries == null) continue;
                        for (Object e : entries) {
                            if (e == null) continue;
                            for (Method em : e.getClass().getMethods()) {
                                if (em.getParameterCount() == 0 && (em.getReturnType() == Text.class || em.getReturnType() == String.class)) {
                                    Object v = em.invoke(e);
                                    if (v != null) {
                                        double pe = parseFirstPriceOnLine(((v instanceof Text t) ? t.getString() : v.toString()).replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
                                        if (pe > 0) return pe;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void recordPriceSample(double ahPrice, double orderPrice) {
        if (ahPrice > 0) { recentAhPrices.add(ahPrice); if (recentAhPrices.size() > 4) recentAhPrices.remove(0); }
        if (orderPrice > 0) { recentOrderPrices.add(orderPrice); if (recentOrderPrices.size() > 4) recentOrderPrices.remove(0); }
    }

    private boolean isMarketPriceChaotic() { return isSeriesChaotic(recentAhPrices) || isSeriesChaotic(recentOrderPrices); }

    private boolean isSeriesChaotic(List<Double> series) {
        if (series.size() < 3) return false;
        int jumps = 0; double minP = series.get(0), maxP = series.get(0);
        for (int i = 1; i < series.size(); i++) {
            double p = series.get(i);
            if (Math.abs(p - series.get(i - 1)) >= 8_000_000) jumps++;
            if (p < minP) minP = p; if (p > maxP) maxP = p;
        }
        return jumps >= 2 || ((maxP - minP) >= 20_000_000 && jumps >= 1);
    }

    private int countAhElytraAtOrBelow(MinecraftClient client, HandledScreen<?> screen, double targetOrderPrice) {
        int c = 0, limit = Math.min(45, screen.getScreenHandler().slots.size());
        long target = Math.round(targetOrderPrice);
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && Math.round(p) <= target) c++;
            }
        }
        return c;
    }

    private int countCompetitorsBelowTarget(MinecraftClient client, HandledScreen<?> screen, double orderPrice) {
        double base = orderPrice > 0 ? orderPrice : (highestOrderPrice > 0 ? highestOrderPrice : 500_000_000);
        double effectiveProfit = Math.max(0, minProfit - ahPriceDropTotal);
        double targetMin = calculateCustomSellPrice(base, effectiveProfit);
        int comp = 0, limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && p < targetMin) comp++;
            }
        }
        return comp;
    }

    private double determineSmartSellPriceMultiPage(MinecraftClient client, HandledScreen<?> screen, double orderPrice) {
        if (state == BotState.SELL_SCAN_AH_READ) {
            ahListingStartMs = System.currentTimeMillis();
            ahPriceDropTotal = 0;
            forceDiscountRelist = false;
        }
        double base = orderPrice > 0 ? orderPrice : (highestOrderPrice > 0 ? highestOrderPrice : 500_000_000);
        double effectiveProfit = Math.max(0, minProfit - ahPriceDropTotal);
        double targetMin = calculateCustomSellPrice(base, effectiveProfit);
        double breakEvenFloor = calculateCustomSellPrice(base, 0);
        double safeFloor = Math.max(breakEvenFloor, calculateCustomSellPrice(base, Math.max(0, effectiveProfit - stopLossReduction)));
        double lowAh = combineLowestPrice(page1LowestAh, scanAhLowestPrice(client, screen));
        int comp = page1CompetitorsBelowTarget + countCompetitorsBelowTarget(client, screen, orderPrice);
        double chosen = (lowAh >= targetMin) ? lowAh : ((lowAh > 0 && comp >= stopLossCompetitorCount) ? Math.max(lowAh, safeFloor) : targetMin);
        if (ahPriceDropTotal > 0) chosen = Math.min(chosen, targetMin);
        return Math.max(breakEvenFloor, chosen);
    }

    private void recordAhSaleFromChat(MinecraftClient client, double actualSoldPrice) {
        double base = myOrderPrice > 0 ? myOrderPrice : (highestOrderPrice > 0 ? highestOrderPrice : actualSoldPrice - minProfit);
        soldCount++;
        lastProfit = calculateNetProfit(base, actualSoldPrice);
        totalProfit += lastProfit;
        unpaidProfitPool += Math.max(0, lastProfit);
        hasActiveOrder = false; hasActiveAhListing = false;
        ahListingStartMs = 0; ahPriceDropTotal = 0; forceDiscountRelist = false;
        logChat(client, "§a§l[CÓ NGƯỜI MUA AH] §eOrder: " + formatMoney(base) + " §7-> §aBán: " + formatMoney(actualSoldPrice) + " §7| Lời: §b+" + formatMoney(lastProfit));

        if (autoPayEnabled && !autoPayTarget.isBlank() && autoPayThreshold > 0 && autoPayAmount > 0 && unpaidProfitPool >= autoPayThreshold) {
            unpaidProfitPool -= autoPayThreshold;
            pendingPayCommand = "pay " + autoPayTarget.trim() + " " + toCommandPrice(autoPayAmount);
        }
    }

    private double parseAmountWithSuffix(String numPart, String suffixPart) {
        try {
            double v = Double.parseDouble(numPart.replace(",", ""));
            if (suffixPart != null) switch (suffixPart.toUpperCase()) {
                case "K" -> v *= 1_000; case "M" -> v *= 1_000_000; case "B" -> v *= 1_000_000_000;
            }
            return Math.round(v);
        } catch (NumberFormatException e) { return -1; }
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        if (noGraphicsMode && running && client.currentScreen == null) {
            ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), 0xE8070A0F);
        }
        if (!showHud) return;
        ctx.fill(6, 6, 262, 100, 0xCC0B0F17);
        ctx.fill(6, 6, 262, 8, running ? 0xFF00E676 : 0xFFFF5252);
        ctx.fill(6, 99, 262, 100, 0xFF00E5FF);
        double avg = soldCount > 0 ? (totalProfit / soldCount) : 0;
        String logBadge = autoLogOnPlayer ? " §c[AutoLog: BẬT]" : " §b[F9: Menu]";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§6§l⚡ AUTO FLIP ELYTRA" + logBadge), 12, 12, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7Trạng thái: " + statusText), 12, 24, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fĐã bán: §b§l" + soldCount + " cái §7| Gần nhất: §a+" + formatMoney(lastProfit)), 12, 36, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTổng lời: §e§l+" + formatMoney(totalProfit) + " §7(TB: " + formatMoney(avg) + ")"), 12, 48, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTiền lời / 1h: §d§l+" + formatMoney(getProfitPerHour()) + "/h"), 12, 60, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7TG: §f" + getActiveTimeFormatted() + " §7| Quỹ Pay: §a" + formatMoney(unpaidProfitPool) + "/" + formatMoney(autoPayThreshold)), 12, 72, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§8[F6] Bật/Tắt Bot  •  [F9] Bảng Settings"), 12, 85, 0xAAAAAA);
    }

    private int findEmptyHotbarSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getStack(i).isEmpty()) return i;
        return 8;
    }

    private boolean hasAnyElytraWithPrice(MinecraftClient client, HandledScreen<?> screen) {
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && extractUnitPrice(client, st) > 0) return true;
        }
        return false;
    }

    private List<String> getServerLoreLinesOnly(MinecraftClient client, ItemStack stack) {
        List<String> result = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text t : lore.lines()) result.add(t.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
        }
        if (result.isEmpty()) {
            Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
            List<Text> tip = stack.getTooltip(ctx, client.player, TooltipType.BASIC);
            for (int i = 1; i < tip.size(); i++) {
                result.add(tip.get(i).getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
            }
        }
        return result;
    }
// === HẾT PHẦN 7 ===
            private boolean isOrderDeliveredInList(MinecraftClient client, ItemStack stack) {
        for (String line : getServerLoreLinesOnly(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("order completed") || plain.contains("1/1 delivered")) return true;
        }
        return false;
    }

    private double getProfitPerHour() {
        long activeMs = accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0);
        return (activeMs < 5000 || totalProfit <= 0) ? 0 : totalProfit / (activeMs / 3_600_000.0);
    }

    private String getActiveTimeFormatted() {
        long s = (accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0)) / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void prepareSignSubmission(String txt, BotState next) {
        this.pendingSignText = txt; this.nextStateAfterSign = next;
        this.guiWaitRetries = 0; this.state = BotState.SUBMIT_SIGN_GUI;
        this.waitTicks = 4 + random.nextInt(3);
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
        for (String line : getServerLoreLinesOnly(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("curse") || plain.contains("vanishing") || plain.contains("binding")) return false;
            if (plain.matches(".*unbreaking\\s+(iii|3)\\b.*") || plain.contains("chậm hỏng iii")) hasUnb3 = true;
            else if (plain.matches(".*unbreaking\\s+(i|ii|1|2)\\b.*")) return false;
            if (plain.contains("mending") || plain.contains("sửa chữa")) hasMend = true;
        }
        return hasUnb3 && hasMend;
    }

    private double scanAhLowestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double low = -1; int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && (low < 0 || p < low)) low = p;
            }
        }
        return low;
    }

    private double scanOrderHighestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double highEnch = -1, highAny = -1;
        double maxValidOrder = (lowestAhPrice > 0) ? (lowestAhPrice * 1.15) : 50_000_000_000.0;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (isStandaloneElytra(st)) {
                double p = extractUnitPrice(client, st);
                if (p >= 1_000_000 && p <= maxValidOrder) {
                    if (p > highAny) highAny = p;
                    if (hasExactOrderEnchants(client, st) && p > highEnch) highEnch = p;
                }
            }
        }
        return highEnch > 0 ? highEnch : highAny;
    }

    private double extractUnitPrice(MinecraftClient client, ItemStack stack) {
        if (!isStandaloneElytra(stack)) return -1;
        List<String> lines = getServerLoreLinesOnly(client, stack);
        for (String line : lines) {
            if (line.contains("/")) continue;
            Matcher em = EACH_PRICE_PATTERN.matcher(line);
            if (em.find()) {
                double p = parseAmountWithSuffix(em.group(1), em.group(2));
                if (p > 0) return p;
            }
            Matcher pm = EXPLICIT_PRICE_PATTERN.matcher(line);
            if (pm.find()) {
                double p = parseAmountWithSuffix(pm.group(1), pm.group(2));
                if (p > 0) return p;
            }
        }
        for (String line : lines) {
            String plain = line.toLowerCase();
            if (plain.contains("/") || plain.contains("paid") || plain.contains("delivered")
                    || plain.contains("fulfilled") || plain.contains("requested")
                    || plain.contains("seller") || plain.contains("buyer")
                    || plain.contains("expire") || plain.contains("worth") || plain.contains("total")) continue;
            double p = parseFirstPriceOnLine(plain);
            if (p >= 100_000) return p;
        }
        return -1;
    }

    private double parseFirstPriceOnLine(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw);
        if (m.find()) return parseAmountWithSuffix(m.group(1), m.group(2));
        return -1;
    }
// === HẾT PHẦN 8A - DÁN TIẾP PHẦN 8B NGAY DƯỚI DÒNG NÀY ===
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

    private void clickSlotIfOpen(MinecraftClient client, int slotId, SlotActionType type) {
        long now = System.currentTimeMillis();
        if (now - lastClickTimeMs < 95) return;
        if (slotId >= 0 && client.currentScreen instanceof HandledScreen<?> s && client.interactionManager != null) {
            lastClickTimeMs = now;
            client.interactionManager.clickSlot(s.getScreenHandler().syncId, slotId, 0, type, client.player);
        }
    }

    private boolean sendCommandSafe(MinecraftClient client, String cmd) {
        if (cmdCooldownTicks > 0) return false;
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(cmd);
            cmdCooldownTicks = 18 + random.nextInt(5); return true;
        }
        return false;
    }

    private void transitionFastAntiSus(BotState next, String status) {
        int fastTick = 3 + random.nextInt(2) + (random.nextInt(4) == 0 ? 1 : 0);
        this.state = next;
        this.waitTicks = fastTick;
        this.statusText = status;
    }

    private void transition(BotState next, int min, int max, String status) {
        this.state = next; this.waitTicks = min + random.nextInt(Math.max(1, max - min + 1)); this.statusText = status;
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

        public FlipConfigScreen(ElytraFlipClient mod) {
            super(Text.literal("Elytra Flip Dashboard"));
            this.mod = mod;
        }

        private String btnTxt(String label, boolean on) {
            return (on ? "§a● " : "§c○ ") + "§f" + label + ": " + (on ? "§a§lBẬT" : "§c§lTẮT");
        }

        @Override
        public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

        @Override
        protected void init() {
            int cx = this.width / 2, py = (this.height - 242) / 2;
            int leftX = cx - 170, rightFieldX = cx + 92, rowY = py + 58;

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Chạy Bot (F6)", mod.running)), b -> {
                mod.toggleBotRunning(MinecraftClient.getInstance());
                b.setMessage(Text.literal(btnTxt("Chạy Bot (F6)", mod.running)));
            }).dimensions(leftX, rowY, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Auto Log Gặp Người", autoLogOnPlayer)), b -> {
                autoLogOnPlayer = !autoLogOnPlayer; b.setMessage(Text.literal(btnTxt("Auto Log Gặp Người", autoLogOnPlayer)));
            }).dimensions(leftX, rowY + 19, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tắt Đồ Họa Siêu Mượt", noGraphicsMode)), b -> {
                noGraphicsMode = !noGraphicsMode; b.setMessage(Text.literal(btnTxt("Tắt Đồ Họa Siêu Mượt", noGraphicsMode)));
            }).dimensions(leftX, rowY + 38, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Auto Pay Khi Đủ Lời", autoPayEnabled)), b -> {
                autoPayEnabled = !autoPayEnabled; b.setMessage(Text.literal(btnTxt("Auto Pay Khi Đủ Lời", autoPayEnabled)));
            }).dimensions(leftX, rowY + 57, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tự Hạ 1M (2p30s)", autoDiscountEnabled)), b -> {
                autoDiscountEnabled = !autoDiscountEnabled; b.setMessage(Text.literal(btnTxt("Tự Hạ 1M (2p30s)", autoDiscountEnabled)));
            }).dimensions(leftX, rowY + 76, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Đè Giá Order (+1M)", autoOutbidEnabled)), b -> {
                autoOutbidEnabled = !autoOutbidEnabled; b.setMessage(Text.literal(btnTxt("Đè Giá Order (+1M)", autoOutbidEnabled)));
            }).dimensions(leftX, rowY + 95, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Tự Lật Trang 2 AH", enableAhPage2)), b -> {
                enableAhPage2 = !enableAhPage2; b.setMessage(Text.literal(btnTxt("Tự Lật Trang 2 AH", enableAhPage2)));
            }).dimensions(leftX, rowY + 114, 162, 18).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(btnTxt("Hiện Bảng HUD Góc", showHud)), b -> {
                showHud = !showHud; b.setMessage(Text.literal(btnTxt("Hiện Bảng HUD Góc", showHud)));
            }).dimensions(leftX, rowY + 133, 162, 18).build());

            int rY = py + 58;
            targetField = new TextFieldWidget(this.textRenderer, rightFieldX, rY, 78, 17, Text.literal(""));
            targetField.setMaxLength(32); targetField.setText(autoPayTarget);
            this.addDrawableChild(targetField);

            thresholdField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 21, 78, 17, Text.literal(""));
            thresholdField.setText(String.valueOf(autoPayThreshold / 1_000_000.0));
            this.addDrawableChild(thresholdField);

            payAmountField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 42, 78, 17, Text.literal(""));
            payAmountField.setText(String.valueOf(autoPayAmount / 1_000_000.0));
            this.addDrawableChild(payAmountField);

            minProfitField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 63, 78, 17, Text.literal(""));
            minProfitField.setText(String.valueOf(minProfit / 1_000_000.0));
            this.addDrawableChild(minProfitField);

            discountSecField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 84, 78, 17, Text.literal(""));
            discountSecField.setText(String.valueOf(discountWaitSec));
            this.addDrawableChild(discountSecField);

            refreshField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 105, 78, 17, Text.literal(""));
            refreshField.setText(String.valueOf(refreshIntervalSec));
            this.addDrawableChild(refreshField);

            tickField = new TextFieldWidget(this.textRenderer, rightFieldX, rY + 126, 78, 17, Text.literal(""));
            tickField.setText(String.valueOf(minRandomTick));
            this.addDrawableChild(tickField);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("§e⟳ Reset Thống Kê Về 0"), b -> {
                mod.soldCount = 0; mod.lastProfit = 0; mod.totalProfit = 0; mod.unpaidProfitPool = 0;
                mod.accumulatedActiveMs = 0; mod.lastResumeTimeMs = System.currentTimeMillis();
            }).dimensions(leftX, py + 214, 162, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("§a§l✔ LƯU CÀI ĐẶT & ĐÓNG"), b -> this.close()).dimensions(cx + 8, py + 214, 162, 20).build());
        }

        private void applyValues() {
            autoPayTarget = targetField.getText().trim();
            try { autoPayThreshold = Math.max(0.1, Double.parseDouble(thresholdField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { autoPayAmount = Math.max(0.1, Double.parseDouble(payAmountField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { minProfit = Math.max(0.1, Double.parseDouble(minProfitField.getText().trim())) * 1_000_000; } catch (Exception ignored) {}
            try { discountWaitSec = Math.max(15, Integer.parseInt(discountSecField.getText().trim())); } catch (Exception ignored) {}
            try { refreshIntervalSec = Math.max(15, Integer.parseInt(refreshField.getText().trim())); } catch (Exception ignored) {}
            try {
                minRandomTick = Math.max(3, Integer.parseInt(tickField.getText().trim()));
                maxRandomTick = minRandomTick + 3;
            } catch (Exception ignored) {}
            mod.saveConfig(MinecraftClient.getInstance());
        }

        @Override
        public void close() { applyValues(); super.close(); }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ctx.fill(0, 0, this.width, this.height, 0xB005070B);
            int cx = this.width / 2, px = cx - 180, py = (this.height - 242) / 2, pw = 360, ph = 242;

            ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF00E5FF);
            ctx.fill(px, py, px + pw, py + ph, 0xF50B0F17);
            ctx.fill(px, py, px + pw, py + 40, 0xFF121824);
            ctx.fill(px, py + 40, px + pw, py + 41, 0xFFFFAA00);
            ctx.fill(cx - 174, py + 45, cx - 4, py + 210, 0x99161F2E);
            ctx.fill(cx + 4, py + 45, cx + 174, py + 210, 0x99161F2E);

            int rowY = py + 63, labelX = cx + 10;
            String[] labels = {
                "§fTên nhận Pay:", "§fĐủ lời thì Pay (M):", "§fSố tiền Pay (M):",
                "§fLời tối thiểu (M):", "§fChờ hạ 1M (Giây):", "§fHủy làm mới (s):", "§fĐộ trễ tay (Tick):"
            };
            for (int i = 0; i < labels.length; i++) {
                int ry = rowY + i * 21;
                ctx.fill(cx + 8, ry - 4, cx + 170, ry + 13, 0x88090D14);
            }

            super.render(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§6§l✦ DONUT ELYTRA FLIP §b§lPRO DASHBOARD §7(F9) ✦"), cx, py + 6, 0xFFFFFF);
            String sCard = "§7Đã bán: §b§l" + mod.soldCount + "  §8|  §7Tổng lời: §e§l+" + mod.formatMoney(mod.totalProfit)
                    + "  §8|  §7Tốc độ: §d§l+" + mod.formatMoney(mod.getProfitPerHour()) + "/h  §8|  §7Quỹ Pay: §a§l" + mod.formatMoney(mod.unpaidProfitPool);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sCard), cx, py + 22, 0xFFFFFF);

            ctx.drawTextWithShadow(this.textRenderer, Text.literal("§b§l⚙ CÔNG TẮC TÍNH NĂNG"), cx - 168, py + 47, 0xFFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("§e§l💸 CẤU HÌNH & AUTO PAY"), cx + 10, py + 47, 0xFFFFFF);

            for (int i = 0; i < labels.length; i++) {
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(labels[i]), labelX, rowY + i * 21, 0xFFFFFF);
            }
        }
    }
}
// === HOÀN TẤT 100% ===
