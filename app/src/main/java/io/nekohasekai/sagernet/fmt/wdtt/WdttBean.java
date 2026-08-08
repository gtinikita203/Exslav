package io.nekohasekai.sagernet.fmt.wdtt;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WdttBean extends AbstractBean {

    public String vkHashes;
    public String password;
    public Integer workers;
    public String mode; // "vpn" (WireGuard) or "rawtun"

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (vkHashes == null) vkHashes = "";
        if (password == null) password = "";
        if (workers == null) workers = 27;
        if (mode == null) mode = "vpn";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(2);
        super.serialize(output);
        output.writeString(vkHashes);
        output.writeString(password);
        output.writeInt(workers);
        output.writeString(mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        vkHashes = input.readString();
        password = input.readString();
        workers = input.readInt();
        if (version >= 2) {
            mode = input.readString();
        } else {
            mode = "vpn";
        }
    }

    @NonNull
    @Override
    public WdttBean clone() {
        return KryoConverters.deserialize(new WdttBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WdttBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public WdttBean newInstance() {
            return new WdttBean();
        }

        @Override
        public WdttBean[] newArray(int size) {
            return new WdttBean[size];
        }
    };
}
