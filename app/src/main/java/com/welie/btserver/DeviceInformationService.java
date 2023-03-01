package com.welie.btserver;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Build;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothPeripheralManager;
import com.welie.blessed.GattStatus;
import com.welie.blessed.ReadResponse;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;


class DeviceInformationService extends BaseService {

    private static final UUID DIS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    private static final UUID MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb");
    private static final UUID MODEL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb");
    private static final UUID SERIAL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb");
//    private static final UUID SECURITY_LEVELS_CHARACTERISTIC_UUID = UUID.fromString("00002BF5-0000-1000-8000-00805f9b34fb");
    private static final UUID UDI_CHARACTERISTIC_UUID = UUID.fromString("00007F3A-0000-1000-8000-00805f9b34fb");

    private @NotNull final BluetoothGattService service = new BluetoothGattService(DIS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

    public DeviceInformationService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        BluetoothGattCharacteristic manufacturer = new BluetoothGattCharacteristic(MANUFACTURER_NAME_CHARACTERISTIC_UUID, PROPERTY_READ, PERMISSION_READ);
        service.addCharacteristic(manufacturer);

        BluetoothGattCharacteristic modelNumber = new BluetoothGattCharacteristic(MODEL_NUMBER_CHARACTERISTIC_UUID, PROPERTY_READ, PERMISSION_READ);
        service.addCharacteristic(modelNumber);

        BluetoothGattCharacteristic serialNumber = new BluetoothGattCharacteristic(SERIAL_NUMBER_CHARACTERISTIC_UUID, PROPERTY_READ, PERMISSION_READ);
        service.addCharacteristic(serialNumber);

//        BluetoothGattCharacteristic securityLevels = new BluetoothGattCharacteristic(SECURITY_LEVELS_CHARACTERISTIC_UUID, PROPERTY_READ, PERMISSION_READ);
//        service.addCharacteristic(securityLevels);

        BluetoothGattCharacteristic udi = new BluetoothGattCharacteristic(UDI_CHARACTERISTIC_UUID, PROPERTY_READ, PERMISSION_READ_ENCRYPTED_MITM);
        service.addCharacteristic(udi);
    }

    @Override
    public ReadResponse onCharacteristicRead(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        final byte[] udiLabel = "Philips POX22-1234".getBytes(StandardCharsets.UTF_8);
        final byte[] udiValue = BluetoothBytesParser.mergeArrays(new byte[]{0x01}, udiLabel, new byte[]{0x00});

        if (characteristic.getUuid().equals(MANUFACTURER_NAME_CHARACTERISTIC_UUID)) {
            return new ReadResponse(GattStatus.SUCCESS, Build.MANUFACTURER.getBytes());
        } else if (characteristic.getUuid().equals(MODEL_NUMBER_CHARACTERISTIC_UUID)) {
            return new ReadResponse(GattStatus.SUCCESS, Build.MODEL.getBytes());
        }  else if (characteristic.getUuid().equals(SERIAL_NUMBER_CHARACTERISTIC_UUID)) {
            return new ReadResponse(GattStatus.SUCCESS, "m1".getBytes());
//        } else if (characteristic.getUuid().equals(SECURITY_LEVELS_CHARACTERISTIC_UUID)) {
//            return new ReadResponse(GattStatus.SUCCESS, new byte[]{0x01, 0x03});
        } else if (characteristic.getUuid().equals(UDI_CHARACTERISTIC_UUID)) {
            return new ReadResponse(GattStatus.SUCCESS, udiValue);
        }
        return super.onCharacteristicRead(central, characteristic);
    }

    @Override
    public @NotNull BluetoothGattService getService() {
        return service;
    }

    @Override
    public String getServiceName() {
        return "Device Information Service";
    }
}
