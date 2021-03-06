package com.greenaddress.greenapi;

import com.blockstream.libwally.Wally;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DeterministicKey;

public abstract class HWWallet extends ISigningWallet {

    @Override
    public DeterministicKey getSubAccountPublicKey(final int subAccount) {
        return getMyKey(subAccount).getPubKey();
    }

    @Override
    public String[] signChallenge(final String challengeString, final String[] challengePath) {

        // Generate a path for the challenge.
        // We use "GA" + 0xB11E as the child path as this allows btchip to skip HID auth.
        final HWWallet child = derive(0x4741b11e); // 0x4741 = Ascii G << 8 + A

        // Generate a message to sign from the challenge
        final String challenge = "greenaddress.it      login " + challengeString;
        final byte[] rawHash = Wally.format_bitcoin_message(challenge.getBytes(),
                                                            Wally.BITCOIN_MESSAGE_FLAG_HASH);
        final Sha256Hash hash = Sha256Hash.wrap(rawHash);

        // Return the path to the caller for them to pass in the server RPC call
        challengePath[0] = "GA";

        // Compute and return the challenge signatures
        final ECKey.ECDSASignature signature = child.signMessage(challenge);
        int recId;
        for (recId = 0; recId < 4; ++recId) {
            final ECKey recovered = ECKey.recoverFromSignature(recId, signature, hash, true);
            if (recovered != null && recovered.equals(child.getPubKey()))
                break;
        }
        return new String[]{signature.r.toString(), signature.s.toString(), String.valueOf(recId)};
    }

    @Override
    public byte[] signBitcoinMessageHash(final byte[] sha256d, final int[] path) {
        if (sha256d.length != Wally.SHA256_LEN)
            return null; // Dont sign anything but a message hash
        if (path.length < 2 || path[0] != 0x4741b11e || path[1] != HDKey.BRANCH_MESSAGES)
            return null; // Dont sign on any path except messages paths

        HWWallet key = this;
        for (int i : path)
            key = key.derive(i);

        return key.signMessage(Wally.hex_from_bytes(sha256d)).encodeToDER();
    }

    private HWWallet getMyKey(final int subAccount) {
        if (subAccount != 0)
            return derive(HARDENED | 3).derive(HARDENED | subAccount);
        return this;
    }

    public byte[] getLocalEncryptionPassword() {
        final byte[] pubkey = derive(PASSWORD_PATH).getPubKey().getPubKey();
        return CryptoHelper.pbkdf2_hmac_sha512(pubkey, PASSWORD_SALT);
    }

    protected Object[] getChallengeArguments(final boolean isTrezor, final NetworkParameters params) {
        final byte[] id = getPubKey().toAddress(params).getHash160();
        final Address addr = new Address(params, id);
        return new Object[]{ "login.get_trezor_challenge", addr.toString(), !isTrezor };
    }

    protected abstract DeterministicKey getPubKey();
    protected abstract HWWallet derive(Integer childNumber);
    protected abstract ECKey.ECDSASignature signMessage(String message);

    public byte[] getGAPath() {
        final HWWallet hdkey = derive(GA_PATH);
        return extendedKeyToPath(hdkey.getPubKey().getPubKey(), hdkey.getPubKey().getChainCode());
    }
}
