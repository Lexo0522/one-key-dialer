package platform

import (
	"os"
	"path/filepath"
	"testing"
)

const samplePbk = "[OtherConn]\nMEDIA=rastapi\nPort=PPPoE9-0\nDevice=WAN Miniport (PPPOE)\n\n" +
	"[PPPoE_Auto]\nMEDIA=rastapi\nPreferredPort=PPPoE3-0\nPreferredDevice=Realtek PCIe GbE\n" +
	"Port=PPPoE3-0\nDevice=Realtek PCIe GbE\n\n"

func TestFindSectionDevice(t *testing.T) {
	h := FindSectionDevice(samplePbk, "PPPoE_Auto")
	if h == nil {
		t.Fatal("expected hint for existing section")
	}
	if h.Port != "PPPoE3-0" || h.Device != "Realtek PCIe GbE" {
		t.Fatalf("unexpected hint: %+v", h)
	}

	if FindSectionDevice(samplePbk, "NoSuchConn") != nil {
		t.Fatal("expected nil for missing section")
	}
	if FindSectionDevice("", "PPPoE_Auto") != nil {
		t.Fatal("expected nil for empty content")
	}
}

func TestCurrentDeviceFallbackToDefault(t *testing.T) {
	m := NewRasModule("PPPoE_Auto", filepath.Join(t.TempDir(), "rasphone.pbk"))
	cur := m.CurrentDevice()
	if cur == nil {
		t.Fatal("CurrentDevice must never be nil")
	}
	if cur.Port != DefaultDevice.Port || cur.Device != DefaultDevice.Device {
		t.Fatalf("expected default device, got %+v", *cur)
	}
}

func TestCurrentDeviceFromPhonebook(t *testing.T) {
	file := filepath.Join(t.TempDir(), "rasphone.pbk")
	if err := os.WriteFile(file, []byte(samplePbk), 0o600); err != nil {
		t.Fatal(err)
	}
	m := NewRasModule("PPPoE_Auto", file)
	cur := m.CurrentDevice()
	if cur.Port != "PPPoE3-0" || cur.Device != "Realtek PCIe GbE" {
		t.Fatalf("unexpected current device: %+v", *cur)
	}
}

func TestListDeviceOptionsAlwaysContainsCurrent(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "rasphone.pbk")
	if err := os.WriteFile(file, []byte(samplePbk), 0o600); err != nil {
		t.Fatal(err)
	}

	m := NewRasModule("PPPoE_Auto", file)
	opts := m.ListDeviceOptions()
	if len(opts) == 0 {
		t.Fatal("device list must never be empty")
	}
	cur := m.CurrentDevice()
	found := false
	for _, o := range opts {
		if o.Port == cur.Port && o.Device == cur.Device {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("current device %+v missing from list %+v", *cur, opts)
	}

	// 显式偏好不在电话簿中时，也必须出现在列表里并成为当前项
	m.SetPreferredDevice(&DeviceHint{Port: "PPPoE7-0", Device: "WAN Miniport (PPPOE)", FromExisting: true})
	opts = m.ListDeviceOptions()
	found = false
	for _, o := range opts {
		if o.Port == "PPPoE7-0" {
			found = true
		}
	}
	if !found {
		t.Fatalf("preferred device missing from list %+v", opts)
	}
	if got := m.CurrentDevice(); got.Port != "PPPoE7-0" {
		t.Fatalf("preferred device should win, got %+v", *got)
	}
}
