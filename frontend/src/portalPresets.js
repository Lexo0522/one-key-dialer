// 门户认证预设模板:选中后预填下方表单字段,字段保持可编辑
// (同一厂商不同学校的部署差异大,模板只是起点)。
// 填充值支持后端模板占位符:{portal} {portalbase} {query} {queryenc}
// {userip} {acid} {username} {password}(及其 :enc 转义变体)。
// generic = 通用,不预填,保留手动模板。

export const PORTAL_PRESETS = [
  { key: 'generic', fill: null },
  {
    // 深澜(Srun):两步 challenge 协议由后端 SRUN 模式实现,
    // 登录地址填门户根地址即可,请求体不参与协议。
    key: 'srun',
    fill: {
      loginUrl: '{portalbase}',
      method: 'SRUN',
      body: '',
      headers: '',
      successHint: ''
    }
  },
  {
    // Dr.COM(web 版):向门户根路径提交表单,成功与否靠认证后复验判定。
    key: 'drcom',
    fill: {
      loginUrl: '{portalbase}',
      method: 'POST',
      body: 'DDDDD={username}&upass={password}&0MKKey=123456&R1=0&R3=0&R6=0&para=00',
      headers: '',
      successHint: ''
    }
  },
  {
    // 锐捷 eportal:queryString 来自门户重定向 URL 的查询串。
    key: 'ruijie',
    fill: {
      loginUrl: '{portalbase}InterFace.do?method=login',
      method: 'POST',
      body: 'userId={username}&passwd={password}&service=&queryString={queryenc}&password={password}',
      headers: '',
      successHint: '"result":"success"'
    }
  }
]

export function getPreset(key) {
  return PORTAL_PRESETS.find((p) => p.key === key) || PORTAL_PRESETS[0]
}
