package service

import (
	"testing"

	"github.com/Lexo0522/one-key-dialer/internal/model"
)

func newSession(accounts ...*model.Account) *AccountSession {
	return &AccountSession{accounts: accounts}
}

func TestPasswordForAccount(t *testing.T) {
	a1 := model.NewAccount("宿舍", "20210001", "pw1", "")
	a2 := model.NewAccount("教室", "20210002", "pw2", "")
	a3 := model.NewAccount("无密", "20210003", "", "")
	s := newSession(a1, a2, a3)

	cases := []struct {
		name     string
		username string
		index    int
		want     string
	}{
		{"同名同位直接命中", "20210002", 1, "pw2"},
		{"重排后跨位按账号名命中", "20210001", 1, "pw1"},
		{"账号被改名后无匹配返回空", "20219999", 0, ""},
		{"空账号名不跨行误配", "", 2, ""},
		{"命中但旧账号本就无密码", "20210003", 2, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := s.PasswordForAccount(c.username, c.index)
			if string(got) != c.want {
				t.Fatalf("PasswordForAccount(%q, %d) = %q, want %q", c.username, c.index, got, c.want)
			}
			model.ClearBytes(got)
		})
	}
}

func TestSnapshotWithPasswordsIsDeepCopy(t *testing.T) {
	s := newSession(model.NewAccount("宿舍", "20210001", "pw1", ""))
	snap := s.SnapshotWithPasswords()
	if len(snap) != 1 || snap[0].Password() != "pw1" {
		t.Fatalf("snapshot 未携带密码: %+v", snap)
	}
	// 清零快照不得影响内存账号
	for _, a := range snap {
		a.ClearPassword()
	}
	if s.accounts[0].Password() != "pw1" {
		t.Fatal("清零快照影响了内存账号密码")
	}
}

// 回归用例：UI 的"是否已设置密码"标志必须对真实账号计算。
// 曾经的 bug 是在 Accounts() 的无密码副本上调用 IsPasswordEmpty()，
// 导致 HasPassword 永远为 false——密码列不显示掩码、编辑框无占位符。
func TestViewsHasPassword(t *testing.T) {
	s := newSession(
		model.NewAccount("宿舍", "20210001", "pw1", ""),
		model.NewAccount("无密", "20210002", "", "备注"),
	)
	views := s.Views()
	if len(views) != 2 {
		t.Fatalf("视图行数 = %d, want 2", len(views))
	}
	if !views[0].HasPassword {
		t.Error("有密码的账号 HasPassword = false，UI 将不显示掩码")
	}
	if views[1].HasPassword {
		t.Error("无密码的账号 HasPassword = true")
	}
	if views[1].Remark != "备注" || views[1].Username != "20210002" {
		t.Errorf("视图字段丢失: %+v", views[1])
	}
}
