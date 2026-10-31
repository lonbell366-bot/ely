package com.donut.elytraflip;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {
    public static double minProfit = 2_000_000, stopLossReduction = 1_000_000;
    public static double outbidSpreadThreshold = 3_000_000, outbidStep = 1_000_000;
    public static double maxCapital = 100_000_000_000.0, ahTaxPercent = 0.0;
    public static int stopLossCompetitorCount = 3;
    public static int orderQuantity = 1, checkIntervalSec = 8, ahYourItemsCheckSec = 30;
    public static int refreshIntervalSec = 60, retryScanSec = 8, volatilePauseSec = 300;
    public static int minRandomTick = 9, maxRandomTick = 13;

    public static int slotYourOrdersBtn = 51, slotNewOrderBtn = 9, slotChooseItemBtn = 12;
    public static int slotSearchSignBtn = 50, slotSelectElytra = 0, slotEnchantUnbreaking = 36;
    public static int slotEnchantMending = 19, slotConfirmEnchant = 52, slotSetAmountBtn = 13;
    public static int slotSetPriceBtn = 14, slotConfirmNewOrder = 16;
    public static int slotEditOrderCollectBox = 13, slotEditOrderCancelBtn = 14, slotCancelOrderConfirmBtn = 16;
    public static int slotAhYourItemsBtn = 51, slotAhConfirmBtn = 11;

    private static KeyBinding toggleKey;
    private boolean running = false, hasActiveOrder = false, hasActiveAhListing = false;
    private boolean needAhRelist = false, forceAhRefresh60s = false, verifyingStabilityAfterPause = false;
    private boolean chatOrderCompletedFlag = false, chatAhSoldFlag = false, cancelWait10sFlag = false;
    private BotState state = BotState.IDLE, nextStateAfterSign = BotState.IDLE;
    private String pendingSignText = "", statusText = "§cĐã Tắt";
    private int waitTicks = 0, guiWaitRetries = 0, ahWatchLoops = 0, lastReadSyncId = -1, cmdCooldownTicks = 0;
    private int monitorTicksElapsed = 0, nextCheckTickTarget = 0, volatileTicksRemaining = 0, soldCount = 0;
    private double lowestAhPrice = -1, highestOrderPrice = -1, myOrderPrice = 0, finalAhSellPrice = 0;
    private double myListedAhPrice = 0, pendingRelistPrice = 0, lastProfit = 0, totalProfit = 0;
    private long accumulatedActiveMs = 0, lastResumeTimeMs = 0, lastSaleRecordedMs = 0;

    private final List<Double> recentAhPrices = new ArrayList<>();
    private final List<Double> recentOrderPrices = new ArrayList<>();
    private final Random random = new Random();
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?");
    private static final Pattern AH_BOUGHT_CHAT_PATTERN = Pattern.compile(
            "(?:bought|purchased)\\s+your\\s+(?:[0-9]+x?\\s+)?elytra\\s+for\\s*\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?",
            Pattern.CASE_INSENSITIVE
    );

    private enum BotState {
        IDLE, SCAN_AH_OPEN, SCAN_AH_READ, SCAN_ORDER_OPEN, SCAN_ORDER_READ, EVALUATE_INITIAL_PROFIT,
        PAUSE_VOLATILE_MARKET,
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

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Flip Elytra", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "Donut Elytra Flip"
        ));
        registerCommands();
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> onChatMessage(message));
    }
// === HẾT PHẦN 1 - DÁN TIẾP PHẦN 2 NGAY DƯỚI DÒNG NÀY ===
    private void onChatMessage(Text message) {
        if (message == null) return;
        String plain = message.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        String lower = plain.toLowerCase();

        if (running && (lower.contains("your elytra order is complete") || lower.contains("delivered you 1 elytra"))) {
            chatOrderCompletedFlag = true;
        }
        if (running && lower.contains("you listed 1 elytra")) {
            hasActiveAhListing = true;
        }

        Matcher boughtMatcher = AH_BOUGHT_CHAT_PATTERN.matcher(plain);
        if (boughtMatcher.find()) {
            long now = System.currentTimeMillis();
            if (now - lastSaleRecordedMs > 1500) {
                double soldPriceFromChat = parseAmountWithSuffix(boughtMatcher.group(1), boughtMatcher.group(2));
                if (soldPriceFromChat > 0) {
                    lastSaleRecordedMs = now;
                    recordAhSaleFromChat(MinecraftClient.getInstance(), soldPriceFromChat);
                    hasActiveAhListing = false;
                    hasActiveOrder = false;
                    chatAhSoldFlag = true;
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

    private void restartCycleFromBeginning(MinecraftClient client, String reasonMsg) {
        chatAhSoldFlag = false;
        chatOrderCompletedFlag = false;
        hasActiveOrder = false;
        hasActiveAhListing = false;
        forceAhRefresh60s = false;
        needAhRelist = false;
        cancelWait10sFlag = false;
        lowestAhPrice = -1;
        highestOrderPrice = -1;
        lastReadSyncId = -1;
        guiWaitRetries = 0;
        ahWatchLoops = 0;
        monitorTicksElapsed = 0;
        if (client.currentScreen != null && client.player != null) {
            client.player.closeHandledScreen();
        }
        transition(BotState.SCAN_AH_OPEN, 10, 15, reasonMsg);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (cmdCooldownTicks > 0) cmdCooldownTicks--;

        while (toggleKey.wasPressed()) {
            if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT)) {
                soldCount = 0; lastProfit = 0; totalProfit = 0;
                accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
            }
            running = !running;
            if (running) {
                lastResumeTimeMs = System.currentTimeMillis();
                recentAhPrices.clear(); recentOrderPrices.clear();
                verifyingStabilityAfterPause = false;
                restartCycleFromBeginning(client, "§aĐang khởi động...");
            } else {
                accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs);
                state = BotState.IDLE; statusText = "§cĐã Tắt";
            }
        }
        if (!running || state == BotState.IDLE) return;

        if (hasActiveOrder && (state == BotState.MONITOR_WAIT || state == BotState.MONITOR_AH_OPEN
                || state == BotState.MONITOR_AH_READ || state == BotState.ORDER_POST_CONFIRM_CLOSE)) {
            monitorTicksElapsed++;
        }

        if (chatAhSoldFlag) {
            restartCycleFromBeginning(client, "§aĐã bán xong! Làm lại từ đầu...");
            return;
        }

        if (!hasActiveAhListing && !isSellingOrAhMonitoringState() && findElytraInInventory(client) != -1) {
            chatOrderCompletedFlag = false; hasActiveOrder = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            transition(BotState.SELL_SCAN_AH_OPEN, 8, 12, "§dCó Elytra -> Bán AH...");
            return;
        }

        if (chatOrderCompletedFlag && !hasActiveAhListing && !isSellingOrAhMonitoringState()
                && state != BotState.CLAIM_OPEN_ORDER && state != BotState.CLAIM_CLICK_YOUR_ORDERS
                && state != BotState.CLAIM_CLICK_COMPLETED_ELYTRA && state != BotState.CLAIM_CLICK_COLLECT_BOX
                && state != BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR) {
            chatOrderCompletedFlag = false;
            if (client.currentScreen != null) client.player.closeHandledScreen();
            transition(BotState.CLAIM_OPEN_ORDER, 6, 10, "§aĐơn xong! Mở /order...");
            return;
        }

        if (waitTicks > 0) { waitTicks--; return; }

        if (!tickScanAndCreateOrder(client)) {
            tickMonitorClaimAndSell(client);
        }
    }
// === HẾT PHẦN 2 - DÁN TIẾP PHẦN 3 NGAY DƯỚI DÒNG NÀY ===
    private boolean tickScanAndCreateOrder(MinecraftClient client) {
        switch (state) {
            case PAUSE_VOLATILE_MARKET -> {
                if (client.currentScreen != null) client.player.closeHandledScreen();
                if (volatileTicksRemaining > 0) {
                    volatileTicksRemaining--;
                    int remSec = volatileTicksRemaining / 20;
                    statusText = "§cGiá loạn! Chờ ổn định (" + remSec + "/" + volatilePauseSec + "s)";
                    return true;
                }
                recentAhPrices.clear();
                recentOrderPrices.clear();
                verifyingStabilityAfterPause = true;
                logChat(client, "§eĐã hết 5 phút chờ! Đang kiểm tra lại xem giá Elytra đã ổn định chưa...");
                transition(BotState.SCAN_AH_OPEN, 6, 10, "§eKiểm tra lại độ ổn định giá...");
                return true;
            }
            case SCAN_AH_OPEN -> {
                if (hasActiveAhListing) { state = BotState.AH_MONITOR_WAIT; return true; }
                if (hasActiveOrder) { state = BotState.MONITOR_AH_OPEN; return true; }
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 8, 12, "§eĐóng bảng cũ...");
                    return true;
                }
                if (!sendCommandSafe(client, "ah ely")) {
                    transition(BotState.SCAN_AH_OPEN, 6, 10, "§eĐợi nhịp lệnh...");
                    return true;
                }
                lowestAhPrice = -1; guiWaitRetries = 0;
                transitionHuman(BotState.SCAN_AH_READ, "§eĐang mở AH...");
                return true;
            }
            case SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && hasAnyElytraWithPrice(client, s)) {
                        lastReadSyncId = syncId;
                        lowestAhPrice = scanAhLowestPrice(client, s);
                        client.player.closeHandledScreen();
                        transitionHuman(BotState.SCAN_ORDER_OPEN, "§eQua xem /order ely...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SCAN_AH_READ, 3, 5, "§eĐợi tải giá AH...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 15, 25, "§cMở lại AH...");
                }
                return true;
            }
            case SCAN_ORDER_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SCAN_ORDER_OPEN, 8, 12, "§eĐợi đóng AH...");
                    return true;
                }
                if (!sendCommandSafe(client, "order ely")) {
                    transition(BotState.SCAN_ORDER_OPEN, 6, 10, "§eĐợi nhịp lệnh...");
                    return true;
                }
                highestOrderPrice = -1; guiWaitRetries = 0;
                transitionHuman(BotState.SCAN_ORDER_READ, "§eĐang mở /order ely...");
                return true;
            }
            case SCAN_ORDER_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && title.contains("order") && hasAnyElytraWithPrice(client, s)) {
                        double price = scanOrderHighestPrice(client, s);
                        if (price > 0) {
                            lastReadSyncId = syncId;
                            highestOrderPrice = price;
                            transition(BotState.EVALUATE_INITIAL_PROFIT, 6, 10, "§eTính tiền lời...");
                            return true;
                        }
                    }
                }
                if (++guiWaitRetries < 28) transition(BotState.SCAN_ORDER_READ, 3, 5, "§eĐợi tải giá Order...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 15, 25, "§cQuét lại...");
                }
                return true;
            }
            case EVALUATE_INITIAL_PROFIT -> {
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 15, "§cĐợi quét lại...");
                    return true;
                }
                recordPriceSample(lowestAhPrice, highestOrderPrice);
                if (isMarketPriceChaotic()) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    volatileTicksRemaining = volatilePauseSec * 20;
                    logChat(client, "§c§l[Cảnh Báo] Giá Elytra nhảy quá lộn xộn! Tạm dừng Order chờ 5 phút (300s)...");
                    state = BotState.PAUSE_VOLATILE_MARKET;
                    return true;
                }
                if (verifyingStabilityAfterPause && recentAhPrices.size() < 2) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    logChat(client, "§eĐang xác nhận độ ổn định giá thêm 1 nhịp nữa...");
                    transition(BotState.SCAN_AH_OPEN, 80, 100, "§eKiểm tra độ ổn định lần 2...");
                    return true;
                }
                verifyingStabilityAfterPause = false;

                double rawDiff = lowestAhPrice - highestOrderPrice;
                double plannedOrderPrice = (Math.round(rawDiff) >= Math.round(outbidSpreadThreshold))
                        ? (highestOrderPrice + outbidStep)
                        : highestOrderPrice;
                double netProfit = calculateNetProfit(plannedOrderPrice, lowestAhPrice);

                logChat(client, "§7Order cao nhất: §e" + formatMoney(highestOrderPrice) + " §7| AH thấp nhất: §a" + formatMoney(lowestAhPrice) + " §7| Sẽ đặt: §b" + formatMoney(plannedOrderPrice) + " §7(Lời: §a+" + formatMoney(netProfit) + "§7)");
                if (Math.round(netProfit) < Math.round(minProfit) || plannedOrderPrice * orderQuantity > maxCapital) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 15, "§eChưa đủ lời (Đợi " + retryScanSec + "s)");
                    return true;
                }
                myOrderPrice = plannedOrderPrice; guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§bĐặt Order " + formatMoney(myOrderPrice) + "...");
                return true;
            }
// === HẾT PHẦN 3 - DÁN TIẾP PHẦN 4 NGAY DƯỚI DÒNG NÀY ===
                    case ORDER_CLICK_YOUR_ORDERS -> {
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    if (sendCommandSafe(client, "order ely")) transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§bMở lại trang Order...");
                    else transition(BotState.ORDER_CLICK_YOUR_ORDERS, 8, 12, "§bĐợi mở Order...");
                    return true;
                }
                clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_CLICK_NEW_ORDER, "§bBấm New Order...");
                return true;
            }
            case ORDER_CLICK_NEW_ORDER -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int existingOrderSlot = findActiveElytraOrderSlot(s);
                    if (existingOrderSlot != -1) {
                        ItemStack existingStack = s.getScreenHandler().getSlot(existingOrderSlot).getStack();
                        double existingPrice = extractUnitPrice(client, existingStack);
                        if (existingPrice > 0) myOrderPrice = existingPrice;
                        if (isOrderDeliveredInList(client, existingStack)) {
                            clickSlotIfOpen(client, existingOrderSlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0;
                            transitionHuman(BotState.CLAIM_CLICK_COLLECT_BOX, "§aĐơn cũ đã xong! Lấy Elytra...");
                            return true;
                        } else {
                            hasActiveOrder = true;
                            monitorTicksElapsed = 0;
                            guiWaitRetries = 0;
                            transition(BotState.ORDER_POST_CONFIRM_CLOSE, 6, 10, "§eĐóng Order qua canh AH liên tục...");
                            return true;
                        }
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "New Order", slotNewOrderBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§bBấm Choose Item...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_NEW_ORDER, 3, 5, "§bĐợi bảng Your Orders...");
                else transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§cThử lại Your Orders...");
                return true;
            }
            case ORDER_CLICK_ITEM_SLOT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack currentItem = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (!currentItem.isEmpty() && currentItem.isOf(Items.ELYTRA)) {
                        guiWaitRetries = 0;
                        transitionHuman(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bĐã có Elytra! Nhập giá...");
                        return true;
                    }
                    clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.ORDER_CLICK_SEARCH_SIGN, "§bBấm Search...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_ITEM_SLOT, 3, 5, "§bĐợi bảng New Order...");
                else transitionHuman(BotState.ORDER_CLICK_NEW_ORDER, "§cThử lại New Order...");
                return true;
            }
            case ORDER_CLICK_SEARCH_SIGN -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("select item")) {
                    if (findActiveElytraOrderSlot(s) != -1) {
                        transition(BotState.ORDER_SELECT_ELYTRA, 4, 6, "§bĐã thấy Elytra!");
                        return true;
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "Search", slotSearchSignBtn), SlotActionType.PICKUP);
                    prepareSignSubmission("ely", BotState.ORDER_SELECT_ELYTRA);
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_SEARCH_SIGN, 3, 5, "§bĐợi bảng Select Item...");
                else transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cBấm lại Choose Item...");
                return true;
            }
            case ORDER_SELECT_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("select item")) {
                        int elySlot = findActiveElytraOrderSlot(s);
                        if (elySlot != -1) {
                            clickSlotIfOpen(client, elySlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0;
                            transitionHuman(BotState.ORDER_ENCH_UNBREAKING, "§bĐã chọn đúng Elytra!");
                            return true;
                        }
                    } else if (title.contains("enchant")) {
                        guiWaitRetries = 0;
                        transition(BotState.ORDER_ENCH_UNBREAKING, 4, 6, "§bChọn Unbreaking III...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_SELECT_ELYTRA, 3, 5, "§bĐợi lọc ra Elytra...");
                else { guiWaitRetries = 0; transitionHuman(BotState.ORDER_CLICK_SEARCH_SIGN, "§eGõ lại chữ ely..."); }
                return true;
            }
            case ORDER_ENCH_UNBREAKING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    clickSlotIfOpen(client, slotEnchantUnbreaking, SlotActionType.PICKUP);
                    transitionHuman(BotState.ORDER_ENCH_MENDING, "§bChọn Mending...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_ENCH_UNBREAKING, 3, 5, "§bĐợi bảng Enchant...");
                else transitionHuman(BotState.ORDER_SELECT_ELYTRA, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_ENCH_MENDING -> {
                clickSlotIfOpen(client, slotEnchantMending, SlotActionType.PICKUP);
                transitionHuman(BotState.ORDER_ENCH_CONFIRM, "§bLưu Enchant...");
                return true;
            }
            case ORDER_ENCH_CONFIRM -> {
                clickSlotIfOpen(client, slotConfirmEnchant, SlotActionType.PICKUP);
                guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bKiểm tra Elytra & Nhập giá...");
                return true;
            }
            case ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (!chosen.isEmpty() && chosen.isOf(Items.ELYTRA)) {
                        clickSlotIfOpen(client, slotSetPriceBtn, SlotActionType.PICKUP);
                        prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.ORDER_CHECK_AMOUNT);
                        return true;
                    }
                    guiWaitRetries = 0;
                    transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cBắt buộc chọn Elytra...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 3, 5, "§bĐợi bảng New Order...");
                else transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_CHECK_AMOUNT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    guiWaitRetries = 0;
                    if (needsAmountInput(client)) {
                        clickSlotIfOpen(client, slotSetAmountBtn, SlotActionType.PICKUP);
                        prepareSignSubmission(String.valueOf(orderQuantity), BotState.ORDER_FINAL_CONFIRM);
                    } else {
                        transitionHuman(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                    }
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CHECK_AMOUNT, 3, 5, "§bĐợi bảng New Order...");
                else transitionHuman(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                return true;
            }
            case ORDER_FINAL_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (chosen.isEmpty() || !chosen.isOf(Items.ELYTRA)) {
                        guiWaitRetries = 0;
                        transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cChọn lại Elytra...");
                        return true;
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotConfirmNewOrder), SlotActionType.PICKUP);
                    hasActiveOrder = true;
                    chatOrderCompletedFlag = false;
                    monitorTicksElapsed = 0;
                    guiWaitRetries = 0;
                    transition(BotState.ORDER_POST_CONFIRM_CLOSE, 10, 14, "§eVừa tạo Order! Mở AH canh liên tục...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_FINAL_CONFIRM, 3, 5, "§bĐợi bấm Confirm Order...");
                else transition(BotState.ORDER_POST_CONFIRM_CLOSE, 8, 12, "§eQua xem AH ngay...");
                return true;
            }
            case ORDER_POST_CONFIRM_CLOSE -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.ORDER_POST_CONFIRM_CLOSE, 8, 10, "§eĐợi đóng hẳn bảng Order...");
                    return true;
                }
                cmdCooldownTicks = 0;
                guiWaitRetries = 0;
                lastReadSyncId = -1;
                transition(BotState.MONITOR_AH_OPEN, 4, 6, "§eMở bảng AH canh liên tục...");
                return true;
            }
            default -> { return false; }
        }
    }
// === HẾT PHẦN 4 - DÁN TIẾP PHẦN 5 NGAY DƯỚI DÒNG NÀY ===
        private void tickMonitorClaimAndSell(MinecraftClient client) {
        switch (state) {
            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    fillAndCloseSign(client, signScreen, pendingSignText);
                    guiWaitRetries = 0;
                    transitionHuman(nextStateAfterSign, statusText);
                } else if (++guiWaitRetries < 20) {
                    transition(BotState.SUBMIT_SIGN_GUI, 3, 5, statusText);
                } else transitionHuman(nextStateAfterSign, statusText);
            }
            case MONITOR_WAIT -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    cancelWait10sFlag = false;
                    transitionHuman(BotState.CANCEL_ORDER_OPEN, "§6Đủ 60s chưa xong! Đi hủy Order...");
                    return;
                }
                cmdCooldownTicks = 0;
                transition(BotState.MONITOR_AH_OPEN, 4, 6, "§eMở bảng AH canh liên tục...");
            }
            // MỞ VÀ GIỮ NGUYÊN BẢNG AH TRÊN MÀN HÌNH ĐỂ CANH LIÊN TỤC (KHÔNG TẮT BẢNG)
            case MONITOR_AH_OPEN -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    cancelWait10sFlag = false;
                    transitionHuman(BotState.CANCEL_ORDER_OPEN, "§6Đủ 60s chưa xong! Đi hủy Order...");
                    return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (!title.contains("order")) {
                        guiWaitRetries = 0;
                        ahWatchLoops = 0;
                        transition(BotState.MONITOR_AH_READ, 4, 6, "§aĐang giữ bảng AH canh giá...");
                        return;
                    }
                    client.player.closeHandledScreen();
                    transition(BotState.MONITOR_AH_OPEN, 6, 9, "§eĐóng bảng Order để mở AH...");
                    return;
                }
                cmdCooldownTicks = 0;
                sendCommandSafe(client, "ah ely");
                lowestAhPrice = -1;
                guiWaitRetries = 0;
                ahWatchLoops = 0;
                transition(BotState.MONITOR_AH_READ, 8, 12, "§aĐang mở AH canh giá liên tục...");
            }
            case MONITOR_AH_READ -> {
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    cancelWait10sFlag = false;
                    transitionHuman(BotState.CANCEL_ORDER_OPEN, "§6Đủ 60s chưa xong! Đi hủy Order...");
                    return;
                }
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    String title = s.getTitle().getString().toLowerCase();
                    if (title.contains("order")) {
                        client.player.closeHandledScreen();
                        cmdCooldownTicks = 0;
                        transition(BotState.MONITOR_AH_OPEN, 8, 12, "§eĐóng bảng Order đè -> Mở lại AH...");
                        return;
                    }
                    if (hasAnyElytraWithPrice(client, s)) {
                        guiWaitRetries = 0;
                        lowestAhPrice = scanAhLowestPrice(client, s);
                        int countLowOrEqual = countAhElytraAtOrBelow(client, s, myOrderPrice);
                        int sec = monitorTicksElapsed / 20;

                        if (countLowOrEqual >= 3) {
                            client.player.closeHandledScreen();
                            logChat(client, "§c[Hủy Khẩn Cấp] Phát hiện " + countLowOrEqual + " Elytra trên AH <= giá Order (" + formatMoney(myOrderPrice) + ")! Lập tức hủy Order và làm lại!");
                            cancelWait10sFlag = false;
                            transitionHuman(BotState.CANCEL_ORDER_OPEN, "§cCó >=3 Elytra <= Order! Hủy làm lại...");
                            return;
                        }

                        // Giữ bảng AH mở liên tục trên màn hình, chỉ gõ lại /ah ely làm mới sau mỗi ~8 giây
                        statusText = "§aCanh AH (<=" + formatMoney(myOrderPrice) + ": " + countLowOrEqual + "/3) " + sec + "/" + refreshIntervalSec + "s";
                        if (++ahWatchLoops < 30) {
                            transition(BotState.MONITOR_AH_READ, 5, 6, statusText);
                        } else {
                            ahWatchLoops = 0;
                            cmdCooldownTicks = 0;
                            sendCommandSafe(client, "ah ely");
                            transition(BotState.MONITOR_AH_READ, 8, 12, statusText);
                        }
                        return;
                    }
                } else {
                    // Nếu bảng bị tắt -> Mở lại ngay lập tức để không bao giờ đứng không!
                    cmdCooldownTicks = 0;
                    transition(BotState.MONITOR_AH_OPEN, 4, 6, "§eMở lại bảng AH ngay...");
                    return;
                }
                if (++guiWaitRetries < 20) {
                    transition(BotState.MONITOR_AH_READ, 3, 5, "§eĐợi tải bảng AH...");
                } else {
                    guiWaitRetries = 0;
                    cmdCooldownTicks = 0;
                    sendCommandSafe(client, "ah ely");
                    transition(BotState.MONITOR_AH_READ, 8, 12, "§eLàm mới lại /ah ely...");
                }
            }
            case CLAIM_OPEN_ORDER -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.CLAIM_OPEN_ORDER, 8, 12, "§aĐóng bảng cũ...");
                    return;
                }
                cmdCooldownTicks = 0;
                sendCommandSafe(client, "order");
                guiWaitRetries = 0;
                transitionHuman(BotState.CLAIM_CLICK_YOUR_ORDERS, "§aBấm vào Your Orders...");
            }
            case CLAIM_CLICK_YOUR_ORDERS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your orders")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, "§aChọn đơn Elytra đã xong...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_YOUR_ORDERS, 3, 5, "§aĐợi bảng Orders...");
                else transitionHuman(BotState.CLAIM_OPEN_ORDER, "§aMở lại /order...");
            }
            case CLAIM_CLICK_COMPLETED_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int myElytraSlot = findActiveElytraOrderSlot(s);
                    if (myElytraSlot != -1) {
                        double p = extractUnitPrice(client, s.getScreenHandler().getSlot(myElytraSlot).getStack());
                        if (p > 0) myOrderPrice = p;
                        clickSlotIfOpen(client, myElytraSlot, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionHuman(BotState.CLAIM_CLICK_COLLECT_BOX, "§aBấm vào nút Collect...");
                        return;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_COMPLETED_ELYTRA, 3, 5, "§aĐợi bảng Your Orders...");
                else transitionHuman(BotState.CLAIM_OPEN_ORDER, "§aThử lại /order...");
            }
            case CLAIM_CLICK_COLLECT_BOX -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Collect", slotEditOrderCollectBox), SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, "§aLấy Elytra vào ô số...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLICK_COLLECT_BOX, 3, 5, "§aĐợi bảng Edit Order...");
                else transitionHuman(BotState.CLAIM_OPEN_ORDER, "§aMở lại /order...");
            }
            case CLAIM_TAKE_ELYTRA_TO_HOTBAR -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("collect")) {
                    int elySlot = findActiveElytraOrderSlot(s);
                    if (elySlot != -1) {
                        clickSlotIfOpen(client, elySlot, SlotActionType.PICKUP);
                        hasActiveOrder = false; guiWaitRetries = 0;
                        transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 8, 12, "§aĐang đóng bảng Order...");
                        return;
                    }
                }
                if (++guiWaitRetries < 18) transition(BotState.CLAIM_TAKE_ELYTRA_TO_HOTBAR, 3, 5, "§aĐợi bảng Collect Items...");
                else { guiWaitRetries = 0; transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 6, 10, "§aKiểm tra túi đồ..."); }
            }
            case CLAIM_CLOSE_AND_WAIT_INV -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 6, 10, "§aThoát bảng Order...");
                    return;
                }
                if (findElytraInInventory(client) != -1) {
                    hasActiveOrder = false;
                    transition(BotState.SELL_SCAN_AH_OPEN, 6, 10, "§dQuét giá AH để bán...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CLAIM_CLOSE_AND_WAIT_INV, 3, 5, "§aĐợi đồng bộ ô đồ...");
                else transitionHuman(BotState.MONITOR_AH_OPEN, "§aTiếp tục canh đơn...");
            }
// === HẾT PHẦN 5 - DÁN TIẾP PHẦN 6 NGAY DƯỚI DÒNG NÀY ===
                      case CANCEL_ORDER_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.CANCEL_ORDER_OPEN, 8, 12, "§cĐóng bảng cũ...");
                    return;
                }
                cmdCooldownTicks = 0;
                sendCommandSafe(client, "order");
                guiWaitRetries = 0;
                transitionHuman(BotState.CANCEL_ORDER_CLICK_YOURS, "§cBấm Your Orders...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.CANCEL_ORDER_CLICK_ELYTRA, "§cChọn Elytra cần hủy...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CANCEL_ORDER_CLICK_YOURS, 3, 5, statusText);
                else transitionHuman(BotState.CANCEL_ORDER_OPEN, statusText);
            }
            case CANCEL_ORDER_CLICK_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) {
                        if (isOrderDeliveredInList(client, s.getScreenHandler().getSlot(mySlot).getStack())) {
                            clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                            guiWaitRetries = 0;
                            transitionHuman(BotState.CLAIM_CLICK_COLLECT_BOX, "§aĐơn đã xong! Lấy Elytra...");
                            return;
                        }
                        clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionHuman(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, "§cBấm nút Cancel đỏ (Ô 14)...");
                        return;
                    } else if (++guiWaitRetries >= 12) {
                        if (volatileTicksRemaining > 0) {
                            client.player.closeHandledScreen();
                            hasActiveOrder = false;
                            state = BotState.PAUSE_VOLATILE_MARKET;
                        } else {
                            restartCycleFromBeginning(client, "§eQuét lại từ đầu...");
                        }
                        return;
                    }
                }
                if (++guiWaitRetries < 15) transition(BotState.CANCEL_ORDER_CLICK_ELYTRA, 3, 5, statusText);
                else transitionHuman(BotState.CANCEL_ORDER_OPEN, statusText);
            }
            case CANCEL_ORDER_CLICK_RED_CANCEL -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("edit order")) {
                    int cancelSlot = findSlotByName(client, "Cancel", slotEditOrderCancelBtn);
                    clickSlotIfOpen(client, cancelSlot, SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, "§cBấm Confirm hủy (Ô 16)...");
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CANCEL_ORDER_CLICK_RED_CANCEL, 3, 5, statusText);
                else transitionHuman(BotState.CANCEL_ORDER_OPEN, statusText);
            }
            case CANCEL_ORDER_CLICK_GREEN_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("cancel order")) {
                    int confirmSlot = findSlotByName(client, "Confirm", slotCancelOrderConfirmBtn);
                    clickSlotIfOpen(client, confirmSlot, SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                    hasActiveOrder = false;
                    lastReadSyncId = -1;
                    if (volatileTicksRemaining > 0) {
                        state = BotState.PAUSE_VOLATILE_MARKET;
                    } else if (cancelWait10sFlag) {
                        cancelWait10sFlag = false;
                        transition(BotState.SCAN_AH_OPEN, 200, 210, "§eĐã hủy đơn! Chờ 10s...");
                    } else {
                        restartCycleFromBeginning(client, "§aĐã hủy Order! Làm lại từ đầu...");
                    }
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CANCEL_ORDER_CLICK_GREEN_CONFIRM, 3, 5, statusText);
                else transitionHuman(BotState.CANCEL_ORDER_OPEN, statusText);
            }
            case SELL_SCAN_AH_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SELL_SCAN_AH_OPEN, 8, 12, "§dĐóng bảng cũ...");
                    return;
                }
                if (!sendCommandSafe(client, "ah ely")) {
                    transition(BotState.SELL_SCAN_AH_OPEN, 6, 10, "§dĐợi nhịp lệnh...");
                    return;
                }
                guiWaitRetries = 0;
                transitionHuman(BotState.SELL_SCAN_AH_READ, "§dQuét giá AH để bán...");
            }
            case SELL_SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && hasAnyElytraWithPrice(client, s)) {
                        lastReadSyncId = syncId;
                        finalAhSellPrice = determineSmartSellPrice(client, s, myOrderPrice);
                        client.player.closeHandledScreen();
                        transitionHuman(BotState.SELL_SELECT_HOTBAR_SLOT, "§dBấm vào ô số cầm Elytra...");
                        return;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SELL_SCAN_AH_READ, 3, 5, "§dĐang tải giá AH...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    finalAhSellPrice = calculateCustomSellPrice(myOrderPrice, minProfit);
                    transitionHuman(BotState.SELL_SELECT_HOTBAR_SLOT, "§dBấm vào ô số cầm Elytra...");
                }
            }
            case SELL_SELECT_HOTBAR_SLOT -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SELL_SELECT_HOTBAR_SLOT, 6, 10, "§dĐóng bảng để cầm Elytra...");
                    return;
                }
                int invSlot = findElytraInInventory(client);
                if (invSlot == -1) {
                    if (hasActiveAhListing) {
                        state = BotState.AH_MONITOR_WAIT;
                    } else {
                        restartCycleFromBeginning(client, "§eKhông thấy Elytra, làm lại từ đầu...");
                    }
                    return;
                }
                if (invSlot >= 0 && invSlot <= 8) {
                    client.player.getInventory().selectedSlot = invSlot;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(invSlot));
                } else if (client.interactionManager != null) {
                    int hotbarTarget = findEmptyHotbarSlot(client);
                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, invSlot, hotbarTarget, SlotActionType.SWAP, client.player);
                    client.player.getInventory().selectedSlot = hotbarTarget;
                    if (client.getNetworkHandler() != null) client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarTarget));
                }
                transitionHuman(BotState.SELL_SEND_AH_CMD, "§aCầm Elytra & /ah sell " + formatMoney(finalAhSellPrice));
            }
            case SELL_SEND_AH_CMD -> {
                if (client.player.getMainHandStack().isOf(Items.ELYTRA)) {
                    if (!sendCommandSafe(client, "ah sell " + toCommandPrice(finalAhSellPrice))) {
                        transition(BotState.SELL_SEND_AH_CMD, 6, 10, statusText);
                        return;
                    }
                    guiWaitRetries = 0;
                    transitionHuman(BotState.SELL_CONFIRM_AH_GUI, "§aĐợi bảng Confirm AH...");
                } else transitionHuman(BotState.SELL_SELECT_HOTBAR_SLOT, statusText);
            }
            case SELL_CONFIRM_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotAhConfirmBtn), SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                    myListedAhPrice = finalAhSellPrice;
                    hasActiveAhListing = true;
                    forceAhRefresh60s = false;
                    monitorTicksElapsed = 0;
                    nextCheckTickTarget = ahYourItemsCheckSec * 20;
                    logChat(client, "§dĐã treo Elytra lên AH giá §a" + formatMoney(myListedAhPrice) + "§d! Đứng chờ người mua (Kiểm tra Your Items mỗi " + ahYourItemsCheckSec + "s)...");
                    transition(BotState.AH_MONITOR_WAIT, 15, 22, "§dChờ mua AH: " + formatMoney(myListedAhPrice));
                    return;
                }
                if (++guiWaitRetries < 20) transition(BotState.SELL_CONFIRM_AH_GUI, 3, 5, "§aĐợi bảng Confirm AH...");
                else transitionHuman(BotState.SELL_SELECT_HOTBAR_SLOT, "§eThử gõ lại /ah sell...");
            }
// === HẾT PHẦN 6 - DÁN TIẾP PHẦN 7 NGAY DƯỚI DÒNG NÀY ===
                       case AH_MONITOR_WAIT -> {
                monitorTicksElapsed++;
                int sec = monitorTicksElapsed / 20;
                statusText = "§dChờ mua AH " + formatMoney(myListedAhPrice) + " (" + sec + "/" + refreshIntervalSec + "s)";
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    forceAhRefresh60s = true;
                    transitionHuman(BotState.AH_CHECK_OPEN, "§6Đủ 60s chưa ai mua! Xem giá & bán lại...");
                    return;
                }
                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += ahYourItemsCheckSec * 20;
                    forceAhRefresh60s = false;
                    transitionHuman(BotState.AH_CHECK_OPEN, "§dKiểm tra AH & Your Items (30s)...");
                }
            }
            case AH_CHECK_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.AH_CHECK_OPEN, 8, 12, statusText);
                    return;
                }
                if (!sendCommandSafe(client, "ah ely")) {
                    transition(BotState.AH_CHECK_OPEN, 6, 10, statusText);
                    return;
                }
                guiWaitRetries = 0;
                transitionHuman(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, "§dXem giá AH...");
            }
            case AH_CHECK_READ_AND_CLICK_YOUR_ITEMS -> {
                if (client.currentScreen instanceof HandledScreen<?> s && !s.getTitle().getString().toLowerCase().contains("your items")) {
                    if (hasAnyElytraWithPrice(client, s)) {
                        double lowAh = scanAhLowestPrice(client, s);
                        double smartPrice = determineSmartSellPrice(client, s, myOrderPrice);

                        if (lowAh > 0 && Math.round(lowAh) < Math.round(myListedAhPrice)) {
                            pendingRelistPrice = smartPrice;
                            needAhRelist = true;
                        } else if (forceAhRefresh60s) {
                            pendingRelistPrice = myListedAhPrice;
                            needAhRelist = true;
                        } else {
                            needAhRelist = false;
                        }

                        int yourItemsSlot = findSlotByName(client, "Your Items", slotAhYourItemsBtn);
                        clickSlotIfOpen(client, yourItemsSlot, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionHuman(BotState.AH_YOUR_ITEMS_INSPECT, "§eKiểm tra Your Items...");
                        return;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.AH_CHECK_READ_AND_CLICK_YOUR_ITEMS, 3, 5, statusText);
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    state = BotState.AH_MONITOR_WAIT;
                }
            }
            case AH_YOUR_ITEMS_INSPECT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your items")) {
                    int myAhElytraSlot = findActiveElytraOrderSlot(s);
                    if (myAhElytraSlot != -1) {
                        if (needAhRelist) {
                            clickSlotIfOpen(client, myAhElytraSlot, SlotActionType.PICKUP);
                            finalAhSellPrice = pendingRelistPrice;
                            forceAhRefresh60s = false;
                            guiWaitRetries = 0;
                            transition(BotState.AH_WAIT_RELIST_INV, 8, 12, "§eĐợi Elytra về ô đồ...");
                        } else {
                            client.player.closeHandledScreen();
                            state = BotState.AH_MONITOR_WAIT;
                        }
                        return;
                    }
                    if (++guiWaitRetries >= 8) {
                        logChat(client, "§a[Your Items] Elytra trên AH đã bán xong! Quay lại làm từ đầu...");
                        restartCycleFromBeginning(client, "§aĐã bán xong! Làm lại từ đầu...");
                        return;
                    }
                }
                if (++guiWaitRetries < 18) transition(BotState.AH_YOUR_ITEMS_INSPECT, 3, 5, statusText);
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    state = BotState.AH_MONITOR_WAIT;
                }
            }
            case AH_WAIT_RELIST_INV -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.AH_WAIT_RELIST_INV, 6, 10, "§eĐóng bảng Your Items...");
                    return;
                }
                if (findElytraInInventory(client) != -1) {
                    logChat(client, "§e[Làm Mới AH] Đã gỡ Elytra về ô đồ! Đăng bán lại giá: §a" + formatMoney(finalAhSellPrice));
                    transitionHuman(BotState.SELL_SELECT_HOTBAR_SLOT, "§eBán lại giá: " + formatMoney(finalAhSellPrice));
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.AH_WAIT_RELIST_INV, 3, 5, "§eĐợi Elytra vào ô đồ...");
                else restartCycleFromBeginning(client, "§aLàm lại từ đầu...");
            }
            default -> {}
        }
    }

    private void recordPriceSample(double ahPrice, double orderPrice) {
        if (ahPrice > 0) {
            recentAhPrices.add(ahPrice);
            if (recentAhPrices.size() > 4) recentAhPrices.remove(0);
        }
        if (orderPrice > 0) {
            recentOrderPrices.add(orderPrice);
            if (recentOrderPrices.size() > 4) recentOrderPrices.remove(0);
        }
    }

    private boolean isMarketPriceChaotic() {
        return isSeriesChaotic(recentAhPrices) || isSeriesChaotic(recentOrderPrices);
    }

    private boolean isSeriesChaotic(List<Double> series) {
        if (series.size() < 3) return false;
        int largeJumps = 0;
        double minP = series.get(0), maxP = series.get(0);
        for (int i = 1; i < series.size(); i++) {
            double p = series.get(i);
            double diff = Math.abs(p - series.get(i - 1));
            if (diff >= 8_000_000) largeJumps++;
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        return largeJumps >= 2 || ((maxP - minP) >= 20_000_000 && largeJumps >= 1);
    }

    private int countAhElytraAtOrBelow(MinecraftClient client, HandledScreen<?> screen, double targetOrderPrice) {
        int count = 0;
        long targetRounded = Math.round(targetOrderPrice);
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && Math.round(p) <= targetRounded) count++;
            }
        }
        return count;
    }

    private void recordAhSaleFromChat(MinecraftClient client, double actualSoldPrice) {
        double baseOrder = myOrderPrice > 0 ? myOrderPrice : (highestOrderPrice > 0 ? highestOrderPrice : actualSoldPrice - minProfit);
        soldCount++;
        lastProfit = calculateNetProfit(baseOrder, actualSoldPrice);
        totalProfit += lastProfit;
        hasActiveOrder = false;
        hasActiveAhListing = false;
        logChat(client, "§a§l[CÓ NGƯỜI MUA AH] §eGiá Order: " + formatMoney(baseOrder) + " §7-> §aGiá Bán: " + formatMoney(actualSoldPrice) + " §7| Lời: §b+" + formatMoney(lastProfit) + " §7| Tổng lời: §e+" + formatMoney(totalProfit));
    }

    private double parseAmountWithSuffix(String numPart, String suffixPart) {
        try {
            double v = Double.parseDouble(numPart.replace(",", ""));
            if (suffixPart != null) {
                switch (suffixPart.toUpperCase()) {
                    case "K" -> v *= 1_000;
                    case "M" -> v *= 1_000_000;
                    case "B" -> v *= 1_000_000_000;
                }
            }
            return Math.round(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        ctx.fill(6, 6, 235, 96, 0x90000000);
        ctx.fill(6, 6, 235, 8, running ? 0xFF55FF55 : 0xFFFF5555);
        double avg = soldCount > 0 ? (totalProfit / soldCount) : 0;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§6§lAUTO FLIP ELYTRA (F6)"), 12, 12, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7Trạng thái: " + statusText), 12, 24, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fĐã bán: §b§l" + soldCount + " cái §7| Gần nhất: §a+" + formatMoney(lastProfit)), 12, 36, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTổng lời: §e§l+" + formatMoney(totalProfit) + " §7(TB: " + formatMoney(avg) + ")"), 12, 48, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTiền lời / 1h: §d§l+" + formatMoney(getProfitPerHour()) + "/h"), 12, 60, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7TG chạy: §f" + getActiveTimeFormatted() + " §7| Tick: " + minRandomTick + "-" + maxRandomTick), 12, 72, 0xFFFFFF);
    }
// === HẾT PHẦN 7 - DÁN TIẾP PHẦN 8 NGAY DƯỚI DÒNG NÀY ===
        private int findEmptyHotbarSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return 8;
    }

    private double determineSmartSellPrice(MinecraftClient client, HandledScreen<?> screen, double orderPrice) {
        double baseOrder = orderPrice > 0 ? orderPrice : (highestOrderPrice > 0 ? highestOrderPrice : 500_000_000);
        double targetMinSell = calculateCustomSellPrice(baseOrder, minProfit);
        double reducedProfit = Math.max(500_000, minProfit - stopLossReduction);
        double safeFloorPrice = calculateCustomSellPrice(baseOrder, reducedProfit);

        double lowAh = -1;
        int competitorsBelowTarget = 0;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());

        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0) {
                    if (lowAh < 0 || p < lowAh) lowAh = p;
                    if (p < targetMinSell) competitorsBelowTarget++;
                }
            }
        }
        if (lowAh >= targetMinSell) return lowAh;
        if (lowAh > 0 && competitorsBelowTarget >= stopLossCompetitorCount) {
            return Math.max(lowAh, safeFloorPrice);
        }
        return targetMinSell;
    }

    private boolean hasAnyElytraWithPrice(MinecraftClient client, HandledScreen<?> screen) {
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && extractUnitPrice(client, st) > 0) return true;
        }
        return false;
    }

    private List<String> getAllItemTextLines(MinecraftClient client, ItemStack stack) {
        List<String> result = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text t : lore.lines()) result.add(t.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
        }
        Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
        for (Text t : stack.getTooltip(ctx, client.player, TooltipType.BASIC)) {
            result.add(t.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
        }
        return result;
    }

    private boolean isOrderDeliveredInList(MinecraftClient client, ItemStack stack) {
        for (String line : getAllItemTextLines(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("order completed") || plain.contains("1/1 delivered")) return true;
        }
        return false;
    }

    private double getProfitPerHour() {
        long activeMs = accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0);
        if (activeMs < 5000 || totalProfit <= 0) return 0;
        return totalProfit / (activeMs / 3_600_000.0);
    }

    private String getActiveTimeFormatted() {
        long totalSec = (accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0)) / 1000;
        return String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    private void prepareSignSubmission(String txt, BotState next) {
        this.pendingSignText = txt; this.nextStateAfterSign = next;
        this.guiWaitRetries = 0; this.state = BotState.SUBMIT_SIGN_GUI;
        this.waitTicks = minRandomTick + random.nextInt(Math.max(1, maxRandomTick - minRandomTick + 1));
    }

    private void fillAndCloseSign(MinecraftClient client, AbstractSignEditScreen signScreen, String line1) {
        try {
            SignBlockEntity signEntity = null;
            String[] messages = null;
            boolean front = true;

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
                String id = entry.getIdAsString().toLowerCase();
                int lvl = enchComp.getLevel(entry);
                if (id.contains("curse") || id.contains("vanishing") || id.contains("binding")) return false;
                if (id.contains("unbreaking")) {
                    if (lvl == 3) hasUnb3 = true;
                    else return false;
                }
                if (id.contains("mending") && lvl >= 1) hasMend = true;
            }
        }
        for (String line : getAllItemTextLines(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("curse") || plain.contains("vanishing") || plain.contains("binding")) return false;
            if (plain.matches(".*unbreaking\\s+(iii|3)\\b.*") || plain.contains("chậm hỏng iii")) hasUnb3 = true;
            else if (plain.matches(".*unbreaking\\s+(i|ii|1|2)\\b.*")) return false;
            if (plain.contains("mending") || plain.contains("sửa chữa")) hasMend = true;
        }
        return hasUnb3 && hasMend;
    }

    private double scanAhLowestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double lowestEnchanted = -1;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && (lowestEnchanted < 0 || p < lowestEnchanted)) lowestEnchanted = p;
            }
        }
        return lowestEnchanted;
    }

    private double scanOrderHighestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double highestEnchanted = -1, highestAny = -1;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA)) {
                double p = extractUnitPrice(client, st);
                if (p <= 0) continue;
                if (p > highestAny) highestAny = p;
                if (hasExactOrderEnchants(client, st) && p > highestEnchanted) highestEnchanted = p;
            }
        }
        return highestEnchanted > 0 ? highestEnchanted : highestAny;
    }

    private double extractUnitPrice(MinecraftClient client, ItemStack stack) {
        double fallback = -1;
        for (String line : getAllItemTextLines(client, stack)) {
            String plain = line.toLowerCase();
            if (plain.contains("paid") || plain.contains("total") || plain.contains("seller") || plain.contains("expire")) continue;
            double p = parsePriceLine(plain);
            if (p > 0) {
                if (plain.contains("each") || plain.contains("price") || plain.contains("/item") || plain.contains("giá")) return p;
                if (fallback < 0) fallback = p;
            }
        }
        return fallback;
    }

    private double parsePriceLine(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw);
        double bestOnLine = -1;
        while (m.find()) {
            double v = parseAmountWithSuffix(m.group(1), m.group(2));
            if (v > bestOnLine) bestOnLine = v;
        }
        return bestOnLine;
    }

    private boolean needsAmountInput(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> s) || slotSetAmountBtn >= s.getScreenHandler().slots.size()) return false;
        ItemStack stack = s.getScreenHandler().getSlot(slotSetAmountBtn).getStack();
        if (stack.isEmpty()) return false;
        for (String line : getAllItemTextLines(client, stack)) {
            if (line.contains("(0)")) return true;
        }
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
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private double calculateCustomSellPrice(double orderPrice, double targetProfit) {
        return Math.ceil((orderPrice + targetProfit) / (1.0 - (ahTaxPercent / 100.0)));
    }

    private double calculateNetProfit(double orderPrice, double ahSellPrice) {
        return Math.round((ahSellPrice * (1.0 - (ahTaxPercent / 100.0))) - orderPrice);
    }

    private int findElytraInInventory(MinecraftClient client) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private void clickSlotIfOpen(MinecraftClient client, int slotId, SlotActionType type) {
        if (slotId >= 0 && client.currentScreen instanceof HandledScreen<?> s && client.interactionManager != null)
            client.interactionManager.clickSlot(s.getScreenHandler().syncId, slotId, 0, type, client.player);
    }

    private boolean sendCommandSafe(MinecraftClient client, String cmd) {
        if (cmdCooldownTicks > 0) return false;
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(cmd);
            cmdCooldownTicks = 28 + random.nextInt(10);
            return true;
        }
        return false;
    }

    private void transitionHuman(BotState next, String status) {
        int extraJitter = (random.nextInt(6) == 0) ? random.nextInt(4) : 0;
        transition(next, minRandomTick + extraJitter, maxRandomTick + extraJitter, status);
    }

    private void transition(BotState next, int min, int max, String status) {
        this.state = next;
        this.waitTicks = min + random.nextInt(Math.max(1, max - min + 1));
        this.statusText = status;
    }

    private String formatMoney(double a) {
        if (a >= 1_000_000_000) return String.format("%.2fB", a / 1_000_000_000);
        if (a >= 1_000_000) return String.format("%.2fM", a / 1_000_000);
        if (a >= 1_000) return String.format("%.1fK", a / 1_000);
        return String.valueOf(Math.round(a));
    }

    private String toCommandPrice(double a) {
        long r = Math.round(a);
        return (r % 1_000_000 == 0) ? (r / 1_000_000) + "m" : String.valueOf(r);
    }

    private void logChat(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal("§6§l[ElytraFlip] §r" + msg), false);
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> d.register(ClientCommandManager.literal("elyflip")
            .then(ClientCommandManager.literal("minprofit").then(ClientCommandManager.argument("m", DoubleArgumentType.doubleArg(0.1)).executes(c -> {
                minProfit = DoubleArgumentType.getDouble(c, "m") * 1_000_000; return 1;
            })))
            .then(ClientCommandManager.literal("maxcapital").then(ClientCommandManager.argument("m", DoubleArgumentType.doubleArg(1)).executes(c -> {
                maxCapital = DoubleArgumentType.getDouble(c, "m") * 1_000_000; return 1;
            })))
            .then(ClientCommandManager.literal("checktime").then(ClientCommandManager.argument("s", IntegerArgumentType.integer(5, 300)).executes(c -> {
                checkIntervalSec = IntegerArgumentType.getInteger(c, "s"); return 1;
            })))
            .then(ClientCommandManager.literal("ahchecktime").then(ClientCommandManager.argument("s", IntegerArgumentType.integer(5, 300)).executes(c -> {
                ahYourItemsCheckSec = IntegerArgumentType.getInteger(c, "s"); return 1;
            })))
            .then(ClientCommandManager.literal("refreshtime").then(ClientCommandManager.argument("s", IntegerArgumentType.integer(15, 600)).executes(c -> {
                refreshIntervalSec = IntegerArgumentType.getInteger(c, "s"); return 1;
            })))
            .then(ClientCommandManager.literal("randomtick")
                .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(4, 100))
                    .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(6, 200)).executes(c -> {
                        minRandomTick = IntegerArgumentType.getInteger(c, "min");
                        maxRandomTick = Math.max(minRandomTick + 1, IntegerArgumentType.getInteger(c, "max"));
                        return 1;
                    }))))
            .then(ClientCommandManager.literal("reset").executes(c -> {
                soldCount = 0; lastProfit = 0; totalProfit = 0;
                accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
                return 1;
            }))
        ));
    }
}
// === HOÀN TẤT 100% ===
        
