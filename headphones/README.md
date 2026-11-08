# Headphone registry

ONYX supports two levels:

1. **Battery / connection status** — works for any Bluetooth headset that exposes
   the standard Battery Service (`0x180F`, characteristic `0x2A19`). No model
   entry is needed.
2. **ANC / Ambient / EQ / one-ear modes** — vendor protocols. These require a
   registry entry and a test device.

## Add a model

Open an issue with the **Headphone support** template and include:

- brand and exact model;
- connection type (BLE / Classic / SPP / USB dongle);
- which app controls it (Sony Headphones Connect, Sennheiser Smart Control, etc.);
- what works and what does not;
- optional: a Bluetooth HCI snoop log or a GATT service dump.

Do **not** post your MAC address, serial number, or personal data. A model name is
enough.

## Registry entry format

```json
{
  "id": "sony-wh-1000xm5",
  "brand": "Sony",
  "model": "WH-1000XM5",
  "transport": "ble",
  "battery": true,
  "anc": false,
  "status": "wanted"
}
```

`status`: `supported`, `experimental`, `wanted`, `unsupported`.

Vendor protocols are implemented in ONYX one model family at a time. An entry in
this file does not automatically add ANC support; it marks the device as requested
or known.
