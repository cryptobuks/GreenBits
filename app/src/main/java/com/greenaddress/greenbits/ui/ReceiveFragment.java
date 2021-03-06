package com.greenaddress.greenbits.ui;


import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.blockstream.libwally.Wally;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.greenaddress.greenapi.ConfidentialAddress;
import com.greenaddress.greenapi.JSONMap;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.QrBitmap;

import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.uri.BitcoinURI;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Observer;

import nordpol.android.OnDiscoveredTagListener;
import nordpol.android.TagDispatcher;


public class ReceiveFragment extends SubaccountFragment implements OnDiscoveredTagListener, AmountFields.OnConversionFinishListener, Exchanger.OnCalculateCommissionFinishListener {
    private static final String TAG = ReceiveFragment.class.getSimpleName();

    private FutureCallback<QrBitmap> mNewAddressCallback;
    private FutureCallback<Void> mNewAddressFinished;
    private QrBitmap mQrCodeBitmap;
    private int mSubaccount;
    private Dialog mQrCodeDialog;
    private TagDispatcher mTagDispatcher;
    private TextView mAddressText;
    private ImageView mAddressImage;
    private TextView mCopyIcon;
    private final Runnable mDialogCB = new Runnable() { public void run() { mQrCodeDialog = null; } };

    private EditText mAmountEdit;
    private EditText mAmountFiatEdit;
    private TextView mAmountFiatWithCommission;
    private String mCurrentAddress = "";
    private Coin mCurrentAmount;
    private BitmapWorkerTask mBitmapWorkerTask;
    private AmountFields mAmountFields;

    private Exchanger mExchanger;
    private boolean mIsExchanger;
    private Button mShowQrCode;
    private Observer mNewTxObserver;
    private Observer mNewBlockObserver;

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume -> " + TAG);

        if (getGAService() != null)
            attachObservers();

        if (mAmountFields != null)
            mAmountFields.setIsPausing(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mAmountFields != null)
            mAmountFields.setIsPausing(true);
        Log.d(TAG, "onPause -> " + TAG);
        mQrCodeDialog = UI.dismiss(getActivity(), mQrCodeDialog);
        if (mTagDispatcher != null)
            mTagDispatcher.disableExclusiveNfc();
        detachObservers();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView -> " + TAG);

        if (isZombieNoView())
            return null;

        final GaActivity gaActivity = getGaActivity();

        mTagDispatcher = TagDispatcher.get(gaActivity, this);
        mTagDispatcher.enableExclusiveNfc();

        mSubaccount = getGAService().getCurrentSubAccount();

        if (savedInstanceState != null)
            mIsExchanger = savedInstanceState.getBoolean("isExchanger", false);

        if (mIsExchanger)
            mView = inflater.inflate(R.layout.fragment_buy, container, false);
        else
            mView = inflater.inflate(R.layout.fragment_receive, container, false);

        mAddressText = UI.find(mView, R.id.receiveAddressText);
        mAddressImage = UI.find(mView, R.id.receiveQrImageView);
        mCopyIcon = UI.find(mView, R.id.receiveCopyIcon);
        mAmountEdit = UI.find(mView, R.id.sendAmountEditText);
        mAmountFiatEdit = UI.find(mView, R.id.sendAmountFiatEditText);

        final View amountFields = UI.find(mView, R.id.amountFields);

        mAmountFields = new AmountFields(getGAService(), getContext(), mView, this);
        if (savedInstanceState != null) {
            final Boolean pausing = savedInstanceState.getBoolean("pausing", false);
            mAmountFields.setIsPausing(pausing);
        }

        UI.disable(mCopyIcon);
        mCopyIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                onCopyClicked();
            }
        });

        mNewAddressCallback = new FutureCallback<QrBitmap>() {
            @Override
            public void onSuccess(final QrBitmap result) {
                if (getActivity() != null)
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            onNewAddressGenerated(result);
                        }
                    });
            }

            @Override
            public void onFailure(final Throwable t) {
                t.printStackTrace();
                if (getActivity() != null)
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            hideWaitDialog();
                            UI.enable(mCopyIcon);
                        }
                    });
            }
        };

        final TextView newAddressIcon = UI.find(mView, R.id.receiveNewAddressIcon);
        newAddressIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                generateNewAddress();
            }
        });
        UI.showIf(getGAService().cfg().getBoolean("showAmountInReceive", false) || mIsExchanger, amountFields);

        mCurrentAddress = "";
        if (savedInstanceState != null)
            mCurrentAddress = savedInstanceState.getString("mCurrentAddress", "");

        if (mIsExchanger) {
            setPageSelected(true);
            mAmountFiatWithCommission = UI.find(mView, R.id.amountFiatWithCommission);
            mExchanger = new Exchanger(getContext(), getGAService(), mView, true, this);
            mShowQrCode = UI.find(mView, R.id.showQrCode);
            mShowQrCode.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View view) {
                    final String amountStr = UI.getText(mAmountFiatWithCommission);
                    final double amount = Double.valueOf(amountStr);
                    if (amount > mExchanger.getFiatInBill()) {
                        UI.toast(getGaActivity(), R.string.noEnoughMoneyInPocket, Toast.LENGTH_LONG);
                        return;
                    }
                    final String amountBtc = UI.getText(mAmountEdit);
                    if (amountBtc.isEmpty() || Double.valueOf(amountBtc) <= 0.0) {
                        UI.toast(getGaActivity(), R.string.invalidAmount, Toast.LENGTH_LONG);
                        return;
                    }
                    generateNewAddress(false, new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(final Void aVoid) {
                            String exchangerAddress = mCurrentAddress;
                            if (getGAService().isElements()) {
                                final String currentBtcAddress = mCurrentAddress.replace("bitcoin:", "").split("\\?")[0];
                                exchangerAddress = ConfidentialAddress.fromBase58(getGAService().getNetworkParameters(), currentBtcAddress)
                                        .getBitcoinAddress(getGAService().getNetworkParameters()).toString();
                            }
                            getGAService().cfg().edit().putBoolean("exchanger_address_" + exchangerAddress, true).apply();
                            final BitmapDrawable bitmapDrawable = new BitmapDrawable(getResources(), mQrCodeBitmap.getQRCode());
                            bitmapDrawable.setFilterBitmap(false);
                            mAddressImage.setImageDrawable(bitmapDrawable);
                            onAddressImageClicked(bitmapDrawable);
                        }

                        @Override
                        public void onFailure(final Throwable t) {
                            t.printStackTrace();
                        }
                    });
                }
            });
        } else if (!mCurrentAddress.isEmpty()) {
            // Preserve current address after flipping orientation
            super.setPageSelected(true);
            final int TRANSPARENT = 0; // Transparent background
            onNewAddressGenerated(new QrBitmap(mCurrentAddress, TRANSPARENT));
        }

        registerReceiver();
        return mView;
    }

    @Override
    public void conversionFinish() {
        if (mIsExchanger && mExchanger != null) {
            mExchanger.conversionFinish();
        } else {
            if (mBitmapWorkerTask != null)
                mBitmapWorkerTask.cancel(true);
            mBitmapWorkerTask = new BitmapWorkerTask(this);
            mBitmapWorkerTask.execute();
        }
    }

    @Override
    public void calculateCommissionFinish() {
        if (mBitmapWorkerTask != null)
            mBitmapWorkerTask.cancel(true);
        mBitmapWorkerTask = new BitmapWorkerTask(this);
        mBitmapWorkerTask.execute();
    }

    static class BitmapWorkerTask extends AsyncTask<Object, Object, Bitmap> {

        private WeakReference<ReceiveFragment> mFragment;

        BitmapWorkerTask(final ReceiveFragment fragment) {
            mFragment = new WeakReference<>(fragment);
        }

        @Override
        protected Bitmap doInBackground(final Object... integers) {
            final ReceiveFragment fragment = mFragment.get();
            if (fragment == null)
                return null;

            final String amount = UI.getText(fragment.mAmountEdit);
            fragment.mCurrentAmount = null;
            if (amount.isEmpty()) {
                if (fragment.mQrCodeBitmap == null)
                    return null;
                return resetBitmap(fragment, fragment.mCurrentAddress);
            }

            try {
                final GaService service = fragment.getGAService();
                fragment.mCurrentAmount = UI.parseCoinValue(service, amount);

                final Address address = Address.fromBase58(service.getNetworkParameters(), fragment.mCurrentAddress);
                final String qrCodeText = BitcoinURI.convertToBitcoinURI(address, fragment.mCurrentAmount, null, null);
                return resetBitmap(fragment, qrCodeText);
            } catch (final ArithmeticException | IllegalArgumentException e) {
                return resetBitmap(fragment, fragment.mCurrentAddress);
            }
        }

        @Override
        protected void onPostExecute(final Bitmap bitmap) {
            if (bitmap == null)
                return;
            final ReceiveFragment fragment = mFragment.get();
            if (fragment == null)
                return;
            final BitmapDrawable bitmapDrawable = new BitmapDrawable(fragment.getResources(), bitmap);
            bitmapDrawable.setFilterBitmap(false);
            fragment.mAddressImage.setImageDrawable(bitmapDrawable);
            fragment.mAddressImage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    fragment.onAddressImageClicked(bitmapDrawable);
                }
            });
        }

        private Bitmap resetBitmap(final ReceiveFragment fragment, final String address) {
            final int TRANSPARENT = 0; // Transparent background
            fragment.mQrCodeBitmap = new QrBitmap(address, TRANSPARENT);
            return fragment.mQrCodeBitmap.getQRCode();
        }
    }

    private void generateNewAddress() {
        generateNewAddress(true, null);
    }

    private void generateNewAddress(final boolean clear, final FutureCallback<Void> onDone) {
        Log.d(TAG, "Generating new address for subaccount " + mSubaccount);
        if (isZombie() || getGAService().isTwoFactorResetActive())
            return;

        Long amount = null;
        if (clear)
            UI.clear(mAmountEdit, mAmountFiatEdit);
        if (mIsExchanger && getGAService().isElements()) {
            // TODO: non-fiat / non-assets values
            final String amountText = UI.getText(mAmountEdit);
            if (amountText.isEmpty())
                return;
            amount = (long) (Double.valueOf(amountText) * 100);
        }
        mCurrentAddress = "";
        UI.disable(mCopyIcon);
        destroyCurrentAddress(clear);
        mNewAddressFinished = onDone;
        final Callable waitFn = new Callable<Void>() {
            @Override
            public Void call() {
                popupWaitDialog(R.string.generating_address);
                return null;
            }
        };
        Futures.addCallback(getGAService().getNewAddressBitmap(mSubaccount, waitFn, amount),
                            mNewAddressCallback, getGAService().getExecutor());
    }

    private void destroyCurrentAddress(final boolean clear) {
        Log.d(TAG, "Destroying address for subaccount " + mSubaccount);
        if (isZombie() || getGAService().isTwoFactorResetActive())
            return;
        mCurrentAddress = "";
        if (clear)
            UI.clear(mAmountEdit, mAmountFiatEdit, mAddressText);
        mAddressImage.setImageBitmap(null);
        UI.hide(mView);
    }

    private void onCopyClicked() {
        // Gets a handle to the clipboard service.
        final GaActivity gaActivity = getGaActivity();
        final ClipboardManager cm;
        cm = (ClipboardManager) gaActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData data = ClipData.newPlainText("data", mQrCodeBitmap.getData());
        cm.setPrimaryClip(data);
        final String text = gaActivity.getString(R.string.toastOnCopyAddress) +
                ' ' + gaActivity.getString(R.string.warnOnPaste);
        gaActivity.toast(text);
    }

    private void onAddressImageClicked(final BitmapDrawable bd) {
        mQrCodeDialog = UI.dismiss(getActivity(), mQrCodeDialog);

        final View v = UI.inflateDialog(this, R.layout.dialog_qrcode);
        if (mIsExchanger) {
            final Button cancelButton = UI.find(v, R.id.qrInDialogCancel);
            UI.show(cancelButton, UI.find(v, R.id.qrInDialogWaiting));
            cancelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View view) {
                    mQrCodeDialog = UI.dismiss(getActivity(), mQrCodeDialog);
                }
            });
        }

        final ImageView qrCode = UI.find(v, R.id.qrInDialogImageView);
        qrCode.setLayoutParams(UI.getScreenLayout(getActivity(), 0.8));

        final Dialog dialog = new Dialog(getActivity());
        dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(v);
        UI.setDialogCloseHandler(dialog, mDialogCB);

        qrCode.setImageDrawable(bd);
        mQrCodeDialog = dialog;
        mQrCodeDialog.show();
    }

    private void onNewAddressGenerated(final QrBitmap result) {
        if (getActivity() == null)
            return;

        if (mBitmapWorkerTask != null) {
            mBitmapWorkerTask.cancel(true);
            mBitmapWorkerTask = null;
        }

        mQrCodeBitmap = result;
        final BitmapDrawable bd = new BitmapDrawable(getResources(), result.getQRCode());
        bd.setFilterBitmap(false);
        mAddressImage.setImageDrawable(bd);

        final String qrData = result.getData();
        if (getGAService().isElements()) {
            mAddressText.setText(String.format("%s\n" +
                            "%s\n%s\n" +
                            "%s\n%s\n" +
                            "%s\n%s",
                    qrData.substring(0, 12),
                    qrData.substring(12, 24),
                    qrData.substring(24, 36),
                    qrData.substring(36, 48),
                    qrData.substring(48, 60),
                    qrData.substring(60, 72),
                    qrData.substring(72)
            ));
            mAddressText.setLines(7);
            mAddressText.setMaxLines(7);
        } else {
            mAddressText.setText(String.format("%s\n%s\n%s", qrData.substring(0, 12),
                    qrData.substring(12, 24), qrData.substring(24)));
        }
        mCurrentAddress = result.getData();

        mAddressImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                onAddressImageClicked(bd);
            }
        });

        hideWaitDialog();
        UI.enable(mCopyIcon);
        UI.show(mView);

        if (mNewAddressFinished != null)
            mNewAddressFinished.onSuccess(null);
    }

    @Override
    public void tagDiscovered(final Tag t) {
        Log.d(TAG, "Tag discovered " + t);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate -> " + TAG);
    }

    @Override
    protected void onSubaccountChanged(final int newSubAccount) {
        mSubaccount = newSubAccount;
        if (isPageSelected())
            generateNewAddress();
        else
            destroyCurrentAddress(true);
    }

    private String getAddressUri() {
        final String addr;
        final NetworkParameters params = getGAService().getNetworkParameters();
        if (getGAService().isElements())
            addr = ConfidentialAddress.fromBase58(params, mCurrentAddress).toString();
        else
            addr = Address.fromBase58(params, mCurrentAddress).toString();
        return BitcoinURI.convertToBitcoinURI(params, addr, mCurrentAmount, null, null);
    }

    @Override
    public void onShareClicked() {
        if (mQrCodeBitmap == null || mQrCodeBitmap.getData().isEmpty() ||
                mCurrentAddress.isEmpty() || !mQrCodeBitmap.getData().equals(mCurrentAddress))
            return;

        final Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, getAddressUri());
        intent.setType("text/plain");
        startActivity(intent);
    }

    public void setPageSelected(final boolean isSelected) {
        final boolean needToRegenerate = isSelected && !isPageSelected();
        super.setPageSelected(isSelected);
        if (needToRegenerate)
            generateNewAddress();
        else if (!isSelected)
            destroyCurrentAddress(true);
    }

    @Override
    public void onViewStateRestored(final Bundle savedInstanceState) {
        Log.d(TAG, "onViewStateRestored -> " + TAG);
        super.onViewStateRestored(savedInstanceState);
        if (mAmountFields != null)
            mAmountFields.setIsPausing(false);
        if (mIsExchanger)
            mExchanger.conversionFinish();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mAmountFields != null)
            outState.putBoolean("pausing", mAmountFields.isPausing());
        outState.putBoolean("isExchanger", mIsExchanger);
        outState.putString("mCurrentAddress", mCurrentAddress);
    }

    public void setIsExchanger(final boolean isExchanger) {
        mIsExchanger = isExchanger;
    }

    @Override
    public void attachObservers() {
        if (mNewTxObserver == null) {
            mNewTxObserver = makeUiObserver(new Runnable() { public void run() { onNewTxBlock(false); } });
            getGAService().addNewTxObserver(mNewTxObserver);
        }
        if (mNewBlockObserver == null) {
            mNewBlockObserver = makeUiObserver(new Runnable() { public void run() { onNewTxBlock(true); } });
            getGAService().addNewBlockObserver(mNewBlockObserver);
        }
        super.attachObservers();
    }

    @Override
    public void detachObservers() {
        super.detachObservers();
        if (mNewTxObserver!= null) {
            getGAService().deleteNewTxObserver(mNewTxObserver);
            mNewTxObserver = null;
        }
        if (mNewBlockObserver!= null) {
            getGAService().deleteNewBlockObserver(mNewBlockObserver);
            mNewBlockObserver = null;
        }
    }

    private void onNewTxBlock(final boolean isBlock) {
        if (mCurrentAddress.isEmpty() || !isPageSelected())
            return;

        final GaService service = getGAService();
        Futures.addCallback(service.getMyTransactions(mSubaccount),
                new FutureCallback<Map<String, Object>>() {
                    @Override
                    public void onSuccess(final Map<String, Object> result) {
                        final List txList = (List) result.get("list");
                        final int currentBlock = ((Integer) result.get("cur_block"));
                        boolean matched = false;
                        for (final Object tx : txList) {
                            try {
                                final JSONMap txJSON = (JSONMap) tx;
                                final ArrayList<String> replacedList = txJSON.get("replaced_by");

                                if (replacedList == null) {
                                    final TransactionItem txItem = new TransactionItem(service, txJSON, currentBlock);
                                    if (!service.isElements()) {
                                        matched = txItem.receivedOn != null && txItem.receivedOn.equals(mCurrentAddress);
                                    } else {
                                        final int subaccount = txItem.receivedOnEp.getInt("subaccount", 0);
                                        final int pointer = txItem.receivedOnEp.getInt("pubkey_pointer");
                                        final String receivedOn = ConfidentialAddress.fromP2SHHash(
                                            service.getNetworkParameters(),
                                            Wally.hash160(service.createOutScript(subaccount, pointer)),
                                            service.getBlindingPubKey(subaccount, pointer)
                                        ).toString();
                                        final String currentBtcAddress = mCurrentAddress.replace("bitcoin:", "").split("\\?")[0];
                                        matched = receivedOn.equals(currentBtcAddress);
                                    }
                                    if (matched) {
                                        final GaActivity gaActivity = getGaActivity();
                                        if (mIsExchanger) {
                                            mExchanger.buyBtc(mExchanger.getAmountWithCommission());
                                            gaActivity.toast(R.string.transactionSubmitted);
                                            gaActivity.finish();
                                        } else {
                                            gaActivity.runOnUiThread(new Runnable() {
                                                public void run() {
                                                    final ViewPager viewPager = UI.find(gaActivity, R.id.container);
                                                    viewPager.setCurrentItem(1);
                                                }
                                            });
                                        }
                                        break;
                                    }
                                }
                            } catch (final ParseException e) {
                                e.printStackTrace();
                            }
                        }
                        if (!isBlock && !matched)
                            getGaActivity().toast(R.string.new_incoming_transaction);
                    }

                    @Override
                    public void onFailure(final Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });
    }
}
