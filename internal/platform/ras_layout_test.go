package platform

import "testing"

// TestRasLayoutInvariants RASDIALPARAMSW 两套布局的偏移/容量不变式。
//
// 与 init() 里的 panic 断言不同：这里把边界值显式列出来，改偏移时能直接
// 看到"哪个字段越界了"，而不是只看到一个 panic 消息。init() 断言保证不会
// 静默错位，这个测试保证出错时错误信息可读。
func TestRasLayoutInvariants(t *testing.T) {
	t.Run("两套布局的位移一致", func(t *testing.T) {
		if got := offUserName24H2 - offUserNameSdk; got != 146 {
			t.Errorf("userName 位移 = %d, want 146（24H2 插入的不透明块）", got)
		}
		if got := offPassword24H2 - offPasswordSdk; got != 146 {
			t.Errorf("password 位移 = %d, want 146", got)
		}
		if got := offDomain24H2 - offDomainSdk; got != 146 {
			t.Errorf("domain 位移 = %d, want 146", got)
		}
	})

	t.Run("SDK 布局各字段紧邻", func(t *testing.T) {
		checks := []struct {
			name         string
			off, cap     int
			wantBoundary int
		}{
			{"entryName", offEntryName, capEntryName, offPhoneNumber},
			{"phoneNumber", offPhoneNumber, capPhoneNum, offCallbackNumber},
			{"callbackNumber", offCallbackNumber, capCallbackNo, offSubEntry},
			{"userName", offUserNameSdk, capUserName, offPasswordSdk},
			{"password", offPasswordSdk, capPassword, offDomainSdk},
		}
		for _, c := range checks {
			if got := c.off + c.cap*2; got != c.wantBoundary {
				t.Errorf("SDK %s: off %d + cap %d*2 = %d, want %d",
					c.name, c.off, c.cap, got, c.wantBoundary)
			}
		}
	})

	t.Run("24H2 布局各字段紧邻", func(t *testing.T) {
		checks := []struct {
			name         string
			off, cap     int
			wantBoundary int
		}{
			{"entryName", offEntryName, capEntryName, offPhoneNumber},
			{"phoneNumber", offPhoneNumber, capPhoneNum, offCallbackNumber},
			{"callbackNumber", offCallbackNumber, capCallbackNo, offSubEntry},
			{"userName", offUserName24H2, capUserNameN, offPassword24H2},
			{"password", offPassword24H2, capPasswordN, offDomain24H2},
		}
		for _, c := range checks {
			if got := c.off + c.cap*2; got != c.wantBoundary {
				t.Errorf("24H2 %s: off %d + cap %d*2 = %d, want %d",
					c.name, c.off, c.cap, got, c.wantBoundary)
			}
		}
	})

	t.Run("szDomain 落在结构体内", func(t *testing.T) {
		if offDomainSdk+maxDomain*2 > structSizeSdk {
			t.Errorf("SDK szDomain 越界: %d > %d", offDomainSdk+maxDomain*2, structSizeSdk)
		}
		if offDomain24H2+maxDomain*2 > structSize24H2 {
			t.Errorf("24H2 szDomain 越界: %d > %d", offDomain24H2+maxDomain*2, structSize24H2)
		}
	})

	t.Run("结构体大小 DWORD 对齐", func(t *testing.T) {
		if structSizeSdk%4 != 0 {
			t.Errorf("structSizeSdk = %d 不是 4 的倍数", structSizeSdk)
		}
		if structSize24H2%4 != 0 {
			t.Errorf("structSize24H2 = %d 不是 4 的倍数", structSize24H2)
		}
	})
}

// TestUses24H2Layout 布局选择只以 build 为判据。
func TestUses24H2Layout(t *testing.T) {
	tests := []struct {
		build int
		want  bool
	}{
		{0, false},
		{22000, false},
		{26099, false},
		{build24H2, true},
		{26101, true},
		{30000, true},
	}
	for _, tt := range tests {
		if got := Uses24H2Layout(tt.build); got != tt.want {
			t.Errorf("Uses24H2Layout(%d) = %v, want %v", tt.build, got, tt.want)
		}
	}
}

// TestPutUTF16Bounds putUTF16 的截断与终止符行为：这是密码落盘前的最后
// 一道关，写越界会踩到相邻字段。
func TestPutUTF16Bounds(t *testing.T) {
	t.Run("正好填满且末位是终止符", func(t *testing.T) {
		buf := make([]byte, 8) // 4 个 UTF-16 字符位
		putUTF16(buf, stringToUTF16("abcd"), 4)
		want := []byte{'a', 0, 'b', 0, 'c', 0, 0, 0}
		for i := range want {
			if buf[i] != want[i] {
				t.Fatalf("buf = %v, want %v", buf, want)
			}
		}
	})

	t.Run("超长被截断不会越界", func(t *testing.T) {
		buf := make([]byte, 6) // 3 个字符位
		putUTF16(buf, stringToUTF16("abcdef"), 3)
		if len(buf) != 6 {
			t.Fatalf("buf 长度被改变: %d", len(buf))
		}
		// 末两位必须是终止符。
		if buf[4] != 0 || buf[5] != 0 {
			t.Errorf("buf 尾部未补终止符: %v", buf)
		}
	})

	t.Run("空值只写终止符", func(t *testing.T) {
		buf := make([]byte, 4)
		putUTF16(buf, nil, 2)
		for i, b := range buf {
			if b != 0 {
				t.Errorf("buf[%d] = %d, want 0", i, b)
			}
		}
	})
}
