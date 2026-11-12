package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;
import com.sparrowwallet.sparrow.MaturityEstimate;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.HashIndexEntry;
import com.sparrowwallet.sparrow.wallet.TransactionEntry;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.text.DecimalFormat;
import java.util.Locale;

class CoinCell extends TreeTableCell<Entry, Number> implements ConfirmationsListener {
    private static final PseudoClass MATURITY_EARLY = PseudoClass.getPseudoClass("coinbase-maturity-early");
    private static final PseudoClass MATURITY_QUARTER = PseudoClass.getPseudoClass("coinbase-maturity-quarter");
    private static final PseudoClass MATURITY_HALF = PseudoClass.getPseudoClass("coinbase-maturity-half");
    private static final PseudoClass MATURITY_LATE = PseudoClass.getPseudoClass("coinbase-maturity-late");
    private static final PseudoClass MATURITY_MATURE = PseudoClass.getPseudoClass("coinbase-maturity-mature");

    private static final String[] NON_MINING_LABEL_KEYWORDS = {
            "neoxex",
            "bisq",
            "robosats",
            "simpleswap",
            "ledger nano x",
            "fixed float",
            "fixedfloat"
    };

    private static final String[] MINING_WALLET_NAMES = {
            "XBT - Lazarus Mining",
            "RouteHash Payouts"
    };

    private static final String DISPLAY_COIN_UNIT = "XBT";

    private final CoinTooltip tooltip;
    private final CoinContextMenu contextMenu;
    private final boolean maturityEnabled;

    private IntegerProperty confirmationsProperty;

    public CoinCell() {
        this(false);
    }

    public CoinCell(boolean maturityEnabled) {
        super();
        this.maturityEnabled = maturityEnabled;
        tooltip = new CoinTooltip();
        tooltip.setShowDelay(Duration.millis(500));
        contextMenu = new CoinContextMenu();
        getStyleClass().add("coin-cell");
        if(OsType.getCurrent() == OsType.MACOS) {
            getStyleClass().add("number-field");
        }
    }

    @Override
    protected void updateItem(Number amount, boolean empty) {
        super.updateItem(amount, empty);

        if(empty || amount == null) {
            setText(null);
            setGraphic(null);
            setTooltip(null);
            setContextMenu(null);
        } else {
            TreeItem<Entry> treeItem = getTreeTableView().getTreeItem(getIndex());
            Entry entry = treeItem.getValue();
            EntryCell.applyRowStyles(this, entry);
            clearMaturityState();

            TransactionEntry maturityTransactionEntry = maturityEnabled ? getTransactionEntry(treeItem) : null;
            boolean miningWallet = isMiningWallet(maturityTransactionEntry);
            CoinbaseMaturityInfo maturityInfo = miningWallet ? CoinbaseMaturityInfo.from(maturityTransactionEntry) : null;
            boolean miningPayout = miningWallet && isMiningPayout(maturityTransactionEntry);

            if(!Config.get().isHideAmounts()) {
                if(maturityInfo != null) {
                    applyMaturityState(this, maturityInfo.status());
                } else if(miningPayout) {
                    applyMaturityState(this, CoinbaseMaturityStatus.MATURE);
                }
            }

            CoinTreeTable coinTreeTable = (CoinTreeTable)getTreeTableView();
            UnitFormat format = coinTreeTable.getUnitFormat();
            BitcoinUnit unit = coinTreeTable.getBitcoinUnit();

            String satsValue = format.formatSatsValue(amount.longValue());
            DecimalFormat decimalFormat = (amount.longValue() == 0L ? format.getBtcFormat() : format.getTableBtcFormat());
            final String btcValue = decimalFormat.format(amount.doubleValue() / Transaction.SATOSHIS_PER_BITCOIN);

            if(Config.get().isHideAmounts()) {
                setText(CoinLabel.HIDDEN_AMOUNT_TEXT);
                setTooltip(null);
                setContextMenu(null);
            } else {
                if(unit.equals(BitcoinUnit.BTC)) {
                    tooltip.setValue(satsValue + " " + BitcoinUnit.SATOSHIS.getLabel());
                    setText(btcValue);
                } else {
                    tooltip.setValue(btcValue + " " + DISPLAY_COIN_UNIT);
                    setText(satsValue);
                }
                setTooltip(tooltip);
                contextMenu.updateAmount(amount);
                setContextMenu(contextMenu);
            }

            if(entry instanceof TransactionEntry transactionEntry) {
                tooltip.showConfirmations(transactionEntry, maturityEnabled);

                if(transactionEntry.isConfirming()) {
                    ConfirmationProgressIndicator arc = new ConfirmationProgressIndicator(transactionEntry.getConfirmations());
                    arc.confirmationsProperty().bind(transactionEntry.confirmationsProperty());
                    setGraphic(arc);
                    setContentDisplay(ContentDisplay.LEFT);
                } else {
                    setGraphic(null);
                }

                if(amount.longValue() < 0) {
                    getStyleClass().add("negative-amount");
                }
            } else if(entry instanceof UtxoEntry) {
                setGraphic(null);
            } else if(entry instanceof HashIndexEntry hashIndexEntry) {
                tooltip.hideConfirmations();

                Region node = new Region();
                node.setPrefWidth(10);
                setGraphic(node);
                setContentDisplay(ContentDisplay.RIGHT);

                if(hashIndexEntry.getType() == HashIndexEntry.Type.INPUT && !Config.get().isHideAmounts()) {
                    setText("-" + getText());
                }
            } else {
                setGraphic(null);
            }
        }
    }

    private void clearMaturityState() {
        clearMaturityState(this);
    }

    private static void clearMaturityState(Control control) {
        control.pseudoClassStateChanged(MATURITY_EARLY, false);
        control.pseudoClassStateChanged(MATURITY_QUARTER, false);
        control.pseudoClassStateChanged(MATURITY_HALF, false);
        control.pseudoClassStateChanged(MATURITY_LATE, false);
        control.pseudoClassStateChanged(MATURITY_MATURE, false);
    }

    private static void applyMaturityState(Control control, CoinbaseMaturityStatus status) {
        clearMaturityState(control);
        control.pseudoClassStateChanged(status.pseudoClass(), true);
    }

    private static TransactionEntry getTransactionEntry(TreeItem<Entry> treeItem) {
        if(treeItem == null) {
            return null;
        }

        if(treeItem.getValue() instanceof TransactionEntry transactionEntry) {
            return transactionEntry;
        }

        TreeItem<Entry> parent = treeItem.getParent();
        if(parent != null && parent.getValue() instanceof TransactionEntry transactionEntry) {
            return transactionEntry;
        }

        return null;
    }

    private static boolean isMiningWallet(TransactionEntry transactionEntry) {
        if(transactionEntry == null) {
            return false;
        }

        String walletName = transactionEntry.getWallet().getName();
        if(walletName == null) {
            return false;
        }

        for(String miningWalletName : MINING_WALLET_NAMES) {
            if(walletName.equals(miningWalletName)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isMiningPayout(TransactionEntry transactionEntry) {
        if(transactionEntry == null || transactionEntry.isCoinbase() || transactionEntry.getValue() <= 0) {
            return false;
        }

        if(hasNonMiningLabel(transactionEntry.getLabel())) {
            return false;
        }

        for(Entry child : transactionEntry.getChildren()) {
            if(child instanceof HashIndexEntry hashIndexEntry
                    && hashIndexEntry.getType() == HashIndexEntry.Type.OUTPUT
                    && hasNonMiningLabel(hashIndexEntry.getLabel())) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasNonMiningLabel(String label) {
        if(label == null || label.isBlank()) {
            return false;
        }

        String normalized = label.toLowerCase(Locale.ROOT);
        for(String keyword : NON_MINING_LABEL_KEYWORDS) {
            if(normalized.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public IntegerProperty getConfirmationsProperty() {
        if(confirmationsProperty == null) {
            confirmationsProperty = new SimpleIntegerProperty();
            confirmationsProperty.addListener((observable, oldValue, newValue) -> {
                if(newValue.intValue() >= BlockTransactionHash.BLOCKS_TO_CONFIRM) {
                    getStyleClass().remove("confirming");
                    confirmationsProperty.unbind();
                }
            });
        }

        return confirmationsProperty;
    }

    private static final class CoinTooltip extends Tooltip {
        private static final double TOOLTIP_WIDTH = 175.0;

        private final IntegerProperty confirmationsProperty = new SimpleIntegerProperty();
        private boolean showConfirmations;
        private boolean maturityEnabled;
        private TransactionEntry transactionEntry;
        private String value;

        private CoinTooltip() {
            setWrapText(true);
            setMinWidth(TOOLTIP_WIDTH);
            setPrefWidth(TOOLTIP_WIDTH);
            setMaxWidth(TOOLTIP_WIDTH);
            setOnShowing(event -> setTooltipText());
        }

        public void setValue(String value) {
            this.value = value;
            setTooltipText();
        }

        public void showConfirmations(TransactionEntry transactionEntry, boolean maturityEnabled) {
            showConfirmations = true;
            this.maturityEnabled = maturityEnabled;
            this.transactionEntry = transactionEntry;

            IntegerProperty txEntryConfirmationsProperty = transactionEntry.confirmationsProperty();
            int confirmations = txEntryConfirmationsProperty.get();
            if(confirmations < BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM) {
                confirmationsProperty.unbind();
                confirmationsProperty.bind(txEntryConfirmationsProperty);
                confirmationsProperty.addListener((observable, oldValue, newValue) -> {
                    setTooltipText();
                    if(newValue.intValue() >= BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM) {
                        confirmationsProperty.unbind();
                    }
                });
            } else {
                confirmationsProperty.unbind();
                confirmationsProperty.set(confirmations);
            }

            setTooltipText();
        }

        public void hideConfirmations() {
            showConfirmations = false;
            maturityEnabled = false;
            transactionEntry = null;
            confirmationsProperty.unbind();
            setTooltipText();
        }

        private void setTooltipText() {
            // Keep Shrike's normal tooltip appearance for every row. Only the Value cell itself is color-coded.
            setStyle("");

            boolean miningWallet = maturityEnabled && showConfirmations && isMiningWallet(transactionEntry);
            CoinbaseMaturityInfo maturityInfo = miningWallet
                    ? CoinbaseMaturityInfo.from(transactionEntry)
                    : null;
            boolean miningPayout = miningWallet && isMiningPayout(transactionEntry);

            if(maturityInfo != null) {
                setText(getMaturityTooltipText(maturityInfo));
            } else if(miningPayout) {
                setText(getMiningPayoutTooltipText());
            } else {
                setText(value + (showConfirmations ? " (" + getConfirmationsDescription() + ")" : ""));
            }
        }

        private String getMaturityTooltipText(CoinbaseMaturityInfo maturityInfo) {
            StringBuilder text = new StringBuilder(value == null ? "" : value);
            text.append("\n\n");
            text.append(maturityInfo.status() == CoinbaseMaturityStatus.MATURE ? "Mature coinbase" : "Immature coinbase");
            text.append("\n")
                    .append(String.format(Locale.ROOT, "%.1f%% mature", maturityInfo.progress() * 100.0))
                    .append("\n")
                    .append(String.format(Locale.ROOT, "%,d / %,d confirmations",
                            maturityInfo.confirmations(), maturityInfo.maturity()));

            if(maturityInfo.blocksRemaining() > 0) {
                text.append("\n")
                        .append(String.format(Locale.ROOT, "%,d blocks remaining", maturityInfo.blocksRemaining()))
                        .append("\n")
                        .append(String.format(Locale.ROOT, "Spendable from block %,d", maturityInfo.spendableFrom()))
                        .append("\n")
                        .append(capitalize(MaturityEstimate.describe(maturityInfo.blocksRemaining())))
                        .append(" remaining");
            } else {
                text.append("\nSpendable");
            }

            return text.toString();
        }

        private String getMiningPayoutTooltipText() {
            return (value == null ? "" : value) + "\n\nMining pool payout\nSpendable";
        }

        private String capitalize(String text) {
            if(text == null || text.isEmpty()) {
                return "";
            }

            return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        public String getConfirmationsDescription() {
            if(transactionEntry == null) {
                return "";
            }

            return ConfirmationsDescription.get(confirmationsProperty.get(), transactionEntry.isCoinbase(),
                    transactionEntry.getBlockTransaction().getHeight(), getCurrentBlockHeight(transactionEntry));
        }
    }

    private enum CoinbaseMaturityStatus {
        EARLY(MATURITY_EARLY),
        QUARTER(MATURITY_QUARTER),
        HALF(MATURITY_HALF),
        LATE(MATURITY_LATE),
        MATURE(MATURITY_MATURE);

        private final PseudoClass pseudoClass;

        CoinbaseMaturityStatus(PseudoClass pseudoClass) {
            this.pseudoClass = pseudoClass;
        }

        public PseudoClass pseudoClass() {
            return pseudoClass;
        }
    }

    private record CoinbaseMaturityInfo(CoinbaseMaturityStatus status, int confirmations, int maturity,
                                        int spendableFrom, int blocksRemaining, double progress) {
        private static CoinbaseMaturityInfo from(TransactionEntry transactionEntry) {
            if(transactionEntry == null || !transactionEntry.isCoinbase()) {
                return null;
            }

            int coinbaseHeight = transactionEntry.getBlockTransaction().getHeight();
            Integer currentBlockHeight = getCurrentBlockHeight(transactionEntry);
            if(coinbaseHeight <= 0 || currentBlockHeight == null) {
                return null;
            }

            int maturity = Network.get().getCoinbaseMaturity(coinbaseHeight);
            int confirmations = Math.max(0, currentBlockHeight - coinbaseHeight + 1);
            int blocksRemaining = Math.max(0, maturity - confirmations);
            int spendableFrom = coinbaseHeight + maturity;
            double progress = maturity <= 0 ? 1.0 : Math.min(1.0, (double)confirmations / maturity);

            CoinbaseMaturityStatus status;
            if(confirmations >= maturity) {
                status = CoinbaseMaturityStatus.MATURE;
            } else if(progress < 0.25) {
                status = CoinbaseMaturityStatus.EARLY;
            } else if(progress < 0.50) {
                status = CoinbaseMaturityStatus.QUARTER;
            } else if(progress < 0.75) {
                status = CoinbaseMaturityStatus.HALF;
            } else {
                status = CoinbaseMaturityStatus.LATE;
            }

            return new CoinbaseMaturityInfo(status, confirmations, maturity, spendableFrom, blocksRemaining, progress);
        }
    }

    private static Integer getCurrentBlockHeight(TransactionEntry transactionEntry) {
        Integer currentBlockHeight = transactionEntry.getWallet().getStoredBlockHeight();
        if(currentBlockHeight == null && !transactionEntry.getWallet().isMasterWallet()) {
            currentBlockHeight = transactionEntry.getWallet().getMasterWallet().getStoredBlockHeight();
        }

        return currentBlockHeight;
    }

    private static class CoinContextMenu extends ContextMenu {
        private Number amount;

        public void updateAmount(Number amount) {
            if(amount.equals(this.amount)) {
                return;
            }

            this.amount = amount;
            getItems().clear();

            MenuItem copySatsValue = new MenuItem("Copy Value in sats");
            copySatsValue.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(amount.toString());
                Clipboard.getSystemClipboard().setContent(content);
            });

            MenuItem copyBtcValue = new MenuItem("Copy Value in XBT");
            copyBtcValue.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                UnitFormat format = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
                content.putString(format.formatBtcValue(amount.longValue()));
                Clipboard.getSystemClipboard().setContent(content);
            });

            getItems().addAll(copySatsValue, copyBtcValue);
        }
    }
}
