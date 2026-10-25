package com.donut.elytraflip;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {

    // ================= CẤU HÌNH MẶC ĐỊNH (Chỉnh được trong game bằng /elyflip) =================
    public static double minProfit = 2_000_000;      // Lời tối thiểu: 2m
    public static double maxCapital = 500_000_000;   // Giới hạn vốn tối đa: 500m
    public static int orderQuantity = 1;             // Số lượng Elytra mỗi order
    public static double ahTaxPercent = 0.0;         // Phí/Thuế AH (%)
    public static int checkIntervalSec = 15;         // Kiểm tra lại sau 15 giây
    public static int refreshIntervalSec = 60;       // Làm mới đơn hàng sau 60 giây
    public static int retryScanSec = 10;             // Quét lại sau 10 giây nếu chưa đủ lời 2m

    // Cấu hình vị trí ô (Slot ID) trong GUI
    public static int slotFirstItem = 0;             // Ô đầu tiên trên /ah và /order
    public static int slotYourOrdersBtn = 49;        // Nút "Your Orders"
    public static int slotMyActiveOrder = 10;        // Ô đơn hàng đang hoạt động
    public static int slotClaimItem = 13;            // Ô rút Elytra khi có người bán
    public static int slotCancelOrder = 15;          // Ô hủy Order (Cancel)
    public static int slotCreateOrderBtn = 50;       // Nút "Create Order"
    public static int slotOrderSearchBtn = 11;       // Nút mở Bảng gỗ tìm vật phẩm
    public static int slotOrderSelectItem = 0;       // Ô chọn Elytra sau khi tìm
    public static int slotOrderSetPriceBtn = 13;     // Nút mở Bảng gỗ nhập giá tiền
    public static int slotOrderSetQtyBtn = 15;       // Nút mở Bảng gỗ nhập số lượng
    public static int slotOrderConfirmBtn = 22;      // Nút xác nhận tạo Order
    public static int slotAhConfirmBtn = 11;         // Nút xác nhận khi gõ /ah sell

    // ================= BIẾN TRẠNG THÁI & THỐNG KÊ =================
    private static KeyBinding toggleKey;
    private boolean running = false;
    private BotState state = BotState.IDLE;
    private BotState nextStateAfterSign = BotState.IDLE;
    private String pendingSignText = "";

    private int waitTicks = 0;
    private int monitorTicksElapsed = 0;
    private int nextCheckTickTarget = 0;
    private boolean isRecheckingMarket = false;

    private double lowestAhPrice = -1;
    private double highestOrderPrice = -1;
    private double myOrderPrice = 0;
    private double finalAhSellPrice = 0;

    // Thống kê Số vật phẩm đã bán & Tiền lời
    private int soldCount = 0;
    private double lastProfit = 0;
    private double totalProfit = 0;
    private String statusText = "§cĐã Tắt";

    private final Random random = new Random();
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9,.]+)\\s*([KMBkmb])?");

    private enum BotState {
        IDLE,
        SCAN_AH_OPEN,
        SCAN_AH_READ,
        SCAN_ORDER_OPEN,
        SCAN_ORDER_READ,
        EVALUATE_INITIAL_PROFIT,

        CREATE_ORDER_OPEN,
        CREATE_ORDER_CLICK_BTN,
        CREATE_ORDER_CLICK_SEARCH,
        CREATE_ORDER_SELECT_ITEM,
        CREATE_ORDER_CLICK_PRICE,
        CREATE_ORDER_CLICK_QTY,
        CREATE_ORDER_CONFIRM,
        SUBMIT_SIGN_GUI,

        MONITOR_WAIT,
        CHECK_MY_ORDER_OPEN,
        CHECK_MY_ORDER_CLICK_YOURS,
        CHECK_MY_ORDER_OPEN_DETAIL,
        CHECK_MY_ORDER_INSPECT,
        EVALUATE_15S_RECHECK,

        CANCEL_ORDER_OPEN,
        CANCEL_ORDER_CLICK_YOURS,
        CANCEL_ORDER_CLICK_ACTIVE,
        CANCEL_ORDER_CLICK_CANCEL,

        SELL_SCAN_AH_OPEN,
        SELL_SCAN_AH_READ,
        SELL_MOVE_ELYTRA_TO_HAND,
        SELL_SEND_AH_CMD,
        SELL_CONFIRM_AH_GUI
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Flip Elytra",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "Donut Elytra Flip"
        ));

        registerCommands();
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        while (toggleKey.wasPressed()) {
            long window = client.getWindow().getHandle();
            boolean shiftHeld = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT);
            if (shiftHeld) {
                soldCount = 0;
                lastProfit = 0;
                totalProfit = 0;
                logChat(client, "§eĐã reset bộ đếm Số Elytra đã bán & Tiền lời về 0!");
            }

            running = !running;
            if (running) {
                isRecheckingMarket = false;
                transition(BotState.SCAN_AH_OPEN, 10, 20, "§aĐang khởi động...");
                logChat(client, "§aĐã BẬT Auto Flip Elytra! Lời tối thiểu: §e" + formatMoney(minProfit) + " §a| Vốn tối đa: §e" + formatMoney(maxCapital));
            } else {
                state = BotState.IDLE;
                statusText = "§cĐã Tắt";
                logChat(client, "§cĐã TẮT Auto Flip Elytra! Đã bán: §b" + soldCount + " cái §c| Tổng lời: §a+" + formatMoney(totalProfit));
            }
        }

        if (!running || state == BotState.IDLE) return;

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        switch (state) {
            case SCAN_AH_OPEN -> {
                statusText = "§eĐang quét giá AH...";
                sendCommand(client, "ah Elytra");
                transition(BotState.SCAN_AH_READ, 25, 35, statusText);
            }
            case SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    lowestAhPrice = extractPriceFromSlot(client, screen, slotFirstItem);
                    client.player.closeHandledScreen();
                } else {
                    lowestAhPrice = -1;
                }
                transition(BotState.SCAN_ORDER_OPEN, 15, 25, "§eĐang quét giá Order...");
            }
            case SCAN_ORDER_OPEN -> {
                sendCommand(client, "order Elytra");
                transition(BotState.SCAN_ORDER_READ, 25, 35, "§eĐang đọc giá Order...");
            }
            case SCAN_ORDER_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    highestOrderPrice = extractPriceFromSlot(client, screen, slotFirstItem);
                    client.player.closeHandledScreen();
                } else {
                    highestOrderPrice = -1;
                }
                BotState next = isRecheckingMarket ? BotState.EVALUATE_15S_RECHECK : BotState.EVALUATE_INITIAL_PROFIT;
                transition(next, 15, 25, "§eĐang tính biên độ lời...");
            }

            case EVALUATE_INITIAL_PROFIT -> {
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    logChat(client, "§cKhông đọc được giá trên GUI. Thử lại sau " + retryScanSec + "s...");
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§cĐợi quét lại...");
                    return;
                }

                double netProfit = calculateNetProfit(highestOrderPrice, lowestAhPrice);
                logChat(client, "§7[Quét Giá] Order Cao Nhất: §e" + formatMoney(highestOrderPrice)
                        + " §7| AH Thấp Nhất: §a" + formatMoney(lowestAhPrice)
                        + " §7| Chênh lệch: §b+" + formatMoney(netProfit));

                if (netProfit < minProfit) {
                    logChat(client, "§eChưa đủ mức lời tối thiểu " + formatMoney(minProfit) + ". Quét lại sau " + retryScanSec + "s...");
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§eChưa đủ lời 2m (Đợi " + retryScanSec + "s)");
                    return;
                }

                if (highestOrderPrice * orderQuantity > maxCapital) {
                    logChat(client, "§cGiá Order (" + formatMoney(highestOrderPrice * orderQuantity) + ") vượt vốn tối đa (" + formatMoney(maxCapital) + ")!");
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§cVượt giới hạn vốn!");
                    return;
                }

                myOrderPrice = highestOrderPrice;
                transition(BotState.CREATE_ORDER_OPEN, 10, 20, "§bĐặt Order: " + formatMoney(myOrderPrice));
            }

            case CREATE_ORDER_OPEN -> {
                logChat(client, "§bĐang đặt Order §e" + orderQuantity + "x Elytra §bgiá §a" + formatMoney(myOrderPrice));
                sendCommand(client, "order");
                transition(BotState.CREATE_ORDER_CLICK_BTN, 25, 35, "§bMở Create Order...");
            }
            case CREATE_ORDER_CLICK_BTN -> {
                clickSlotIfOpen(client, slotCreateOrderBtn, SlotActionType.PICKUP);
                transition(BotState.CREATE_ORDER_CLICK_SEARCH, 20, 30, "§bTìm kiếm Elytra...");
            }
            case CREATE_ORDER_CLICK_SEARCH -> {
                clickSlotIfOpen(client, slotOrderSearchBtn, SlotActionType.PICKUP);
                prepareSignSubmission("Elytra", BotState.CREATE_ORDER_SELECT_ITEM);
            }
            case CREATE_ORDER_SELECT_ITEM -> {
                clickSlotIfOpen(client, slotOrderSelectItem, SlotActionType.PICKUP);
                transition(BotState.CREATE_ORDER_CLICK_PRICE, 20, 30, "§bNhập giá Order...");
            }
            case CREATE_ORDER_CLICK_PRICE -> {
                clickSlotIfOpen(client, slotOrderSetPriceBtn, SlotActionType.PICKUP);
                prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.CREATE_ORDER_CLICK_QTY);
            }
            case CREATE_ORDER_CLICK_QTY -> {
                clickSlotIfOpen(client, slotOrderSetQtyBtn, SlotActionType.PICKUP);
                prepareSignSubmission(String.valueOf(orderQuantity), BotState.CREATE_ORDER_CONFIRM);
            }
            case CREATE_ORDER_CONFIRM -> {
                clickSlotIfOpen(client, slotOrderConfirmBtn, SlotActionType.PICKUP);
                client.player.closeHandledScreen();
                monitorTicksElapsed = 0;
                nextCheckTickTarget = checkIntervalSec * 20;
                transition(BotState.MONITOR_WAIT, 10, 20, "§aCanh Order: " + formatMoney(myOrderPrice));
            }

            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    submitSignPacket(client, signScreen, pendingSignText);
                    transition(nextStateAfterSign, 20, 30, statusText);
                } else {
                    transition(nextStateAfterSign, 15, 25, statusText);
                }
            }

            case MONITOR_WAIT -> {
                monitorTicksElapsed++;
                int secElapsed = monitorTicksElapsed / 20;
                statusText = "§aCanh Order " + formatMoney(myOrderPrice) + " (" + secElapsed + "/" + refreshIntervalSec + "s)";

                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += checkIntervalSec * 20;
                    transition(BotState.CHECK_MY_ORDER_OPEN, 5, 10, "§bKiểm tra đơn (" + secElapsed + "s)...");
                }
            }
            case CHECK_MY_ORDER_OPEN -> {
                sendCommand(client, "order");
                transition(BotState.CHECK_MY_ORDER_CLICK_YOURS, 25, 35, statusText);
            }
            case CHECK_MY_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, slotYourOrdersBtn, SlotActionType.PICKUP);
                transition(BotState.CHECK_MY_ORDER_OPEN_DETAIL, 20, 30, statusText);
            }
            case CHECK_MY_ORDER_OPEN_DETAIL -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    ItemStack orderSlot = screen.getScreenHandler().getSlot(slotMyActiveOrder).getStack();
                    if (orderSlot.isEmpty()) {
                        client.player.closeHandledScreen();
                        isRecheckingMarket = false;
                        transition(BotState.SCAN_AH_OPEN, 20, 30, "§eĐơn trống, quét lại...");
                        return;
                    }
                    clickSlotIfOpen(client, slotMyActiveOrder, SlotActionType.PICKUP);
                    transition(BotState.CHECK_MY_ORDER_INSPECT, 20, 30, statusText);
                }
            }
            case CHECK_MY_ORDER_INSPECT -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    ItemStack claimStack = screen.getScreenHandler().getSlot(slotClaimItem).getStack();
                    if (!claimStack.isEmpty() && claimStack.isOf(Items.ELYTRA)) {
                        logChat(client, "§a§lĐã có người bán Elytra vào Order! Đang rút về túi đồ...");
                        clickSlotIfOpen(client, slotClaimItem, SlotActionType.QUICK_MOVE);
                        client.player.closeHandledScreen();
                        transition(BotState.SELL_SCAN_AH_OPEN, 20, 30, "§dChuẩn bị bán lên AH...");
                        return;
                    }
                    client.player.closeHandledScreen();
                }

                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    logChat(client, "§6[Làm mới " + refreshIntervalSec + "s] Chưa có người bán. Đang hủy đơn cũ và tạo lại theo giá mới...");
                    isRecheckingMarket = false;
                    transition(BotState.CANCEL_ORDER_OPEN, 15, 25, "§6Làm mới Order 60s...");
                } else {
                    isRecheckingMarket = true;
                    transition(BotState.SCAN_AH_OPEN, 15, 25, "§eKiểm tra giá 15s...");
                }
            }

            case EVALUATE_15S_RECHECK -> {
                isRecheckingMarket = false;
                int secElapsed = monitorTicksElapsed / 20;
                if (highestOrderPrice > 0 && lowestAhPrice > 0) {
                    if (highestOrderPrice <= myOrderPrice) {
                        logChat(client, "§a[Kiểm tra " + secElapsed + "s] Order của bạn (" + formatMoney(myOrderPrice) + ") vẫn là giá cao nhất. Giữ nguyên!");
                        state = BotState.MONITOR_WAIT;
                    } else {
                        double newMargin = calculateNetProfit(highestOrderPrice, lowestAhPrice);
                        if (newMargin >= minProfit && (highestOrderPrice * orderQuantity) <= maxCapital) {
                            logChat(client, "§e[Kiểm tra " + secElapsed + "s] Có người đặt cao hơn (" + formatMoney(highestOrderPrice) + "), vẫn đủ lời +" + formatMoney(newMargin) + ". Đang hủy và đặt lại!");
                            myOrderPrice = highestOrderPrice;
                            isRecheckingMarket = true;
                            transition(BotState.CANCEL_ORDER_OPEN, 15, 25, "§eCập nhật giá Order mới...");
                        } else {
                            logChat(client, "§c[Kiểm tra " + secElapsed + "s] Có người đè giá lên " + formatMoney(highestOrderPrice) + " nhưng không đủ lời 2m. Giữ nguyên đơn cũ!");
                            state = BotState.MONITOR_WAIT;
                        }
                    }
                } else {
                    state = BotState.MONITOR_WAIT;
                }
            }

            case CANCEL_ORDER_OPEN -> {
                sendCommand(client, "order");
                transition(BotState.CANCEL_ORDER_CLICK_YOURS, 25, 35, "§cĐang hủy Order cũ...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, slotYourOrdersBtn, SlotActionType.PICKUP);
                transition(BotState.CANCEL_ORDER_CLICK_ACTIVE, 20, 30, statusText);
            }
            case CANCEL_ORDER_CLICK_ACTIVE -> {
                clickSlotIfOpen(client, slotMyActiveOrder, SlotActionType.PICKUP);
                transition(BotState.CANCEL_ORDER_CLICK_CANCEL, 20, 30, statusText);
            }
            case CANCEL_ORDER_CLICK_CANCEL -> {
                clickSlotIfOpen(client, slotCancelOrder, SlotActionType.PICKUP);
                client.player.closeHandledScreen();
                if (isRecheckingMarket) {
                    isRecheckingMarket = false;
                    transition(BotState.CREATE_ORDER_OPEN, 20, 30, "§bĐặt lại giá mới...");
                } else {
                    transition(BotState.SCAN_AH_OPEN, 20, 30, "§eQuét giá làm mới 60s...");
                }
            }

            case SELL_SCAN_AH_OPEN -> {
                statusText = "§dQuét giá AH để bán...";
                sendCommand(client, "ah Elytra");
                transition(BotState.SELL_SCAN_AH_READ, 25, 35, statusText);
            }
            case SELL_SCAN_AH_READ -> {
                double currentLowAh = -1;
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    currentLowAh = extractPriceFromSlot(client, screen, slotFirstItem);
                    client.player.closeHandledScreen();
                }
                double minSellPrice = calculateMinSellPrice(myOrderPrice);
                finalAhSellPrice = (currentLowAh > minSellPrice) ? currentLowAh : minSellPrice;
                transition(BotState.SELL_MOVE_ELYTRA_TO_HAND, 15, 25, "§dĐưa Elytra xuống tay...");
            }
            case SELL_MOVE_ELYTRA_TO_HAND -> {
                int elytraSlot = findElytraInInventory(client);
                if (elytraSlot == -1) {
                    logChat(client, "§cKhông tìm thấy Elytra trong túi đồ! Quay lại quét thị trường...");
                    transition(BotState.SCAN_AH_OPEN, 30, 45, "§eQuét lại...");
                    return;
                }
                client.player.getInventory().selectedSlot = 0;
                if (elytraSlot != 0 && client.interactionManager != null) {
                    int containerSlot = elytraSlot < 9 ? elytraSlot + 36 : elytraSlot;
                    client.interactionManager.clickSlot(
                            client.player.playerScreenHandler.syncId,
                            containerSlot,
                            0,
                            SlotActionType.SWAP,
                            client.player
                    );
                }
                transition(BotState.SELL_SEND_AH_CMD, 15, 2
