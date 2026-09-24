export namespace main {
	
	export class AccountDTO {
	    name: string;
	    username: string;
	    password?: string;
	    remark: string;
	    hasPassword: boolean;
	
	    static createFrom(source: any = {}) {
	        return new AccountDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.name = source["name"];
	        this.username = source["username"];
	        this.password = source["password"];
	        this.remark = source["remark"];
	        this.hasPassword = source["hasPassword"];
	    }
	}
	export class AppState {
	    version: string;
	    displayVersion: string;
	    settings: model.Settings;
	    accounts: AccountDTO[];
	    currentIndex: number;
	    online: boolean;
	    history: model.HistoryRecord[];
	    logs: service.LogLine[];
	    autoStartEnabled: boolean;
	    theme: string;
	    lang: string;
	    dataDir: string;
	    updatesDir: string;
	
	    static createFrom(source: any = {}) {
	        return new AppState(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.displayVersion = source["displayVersion"];
	        this.settings = this.convertValues(source["settings"], model.Settings);
	        this.accounts = this.convertValues(source["accounts"], AccountDTO);
	        this.currentIndex = source["currentIndex"];
	        this.online = source["online"];
	        this.history = this.convertValues(source["history"], model.HistoryRecord);
	        this.logs = this.convertValues(source["logs"], service.LogLine);
	        this.autoStartEnabled = source["autoStartEnabled"];
	        this.theme = source["theme"];
	        this.lang = source["lang"];
	        this.dataDir = source["dataDir"];
	        this.updatesDir = source["updatesDir"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class DeviceOption {
	    port: string;
	    device: string;
	    existing: boolean;
	    default: boolean;
	
	    static createFrom(source: any = {}) {
	        return new DeviceOption(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.port = source["port"];
	        this.device = source["device"];
	        this.existing = source["existing"];
	        this.default = source["default"];
	    }
	}
	export class ProbeResult {
	    ok: boolean;
	    line: string;
	    mode: string;
	    error: string;
	
	    static createFrom(source: any = {}) {
	        return new ProbeResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.line = source["line"];
	        this.mode = source["mode"];
	        this.error = source["error"];
	    }
	}

}

export namespace model {
	
	export class HistoryRecord {
	    time: string;
	    operation: string;
	    account: string;
	    result: string;
	    duration: string;
	    traffic: string;
	
	    static createFrom(source: any = {}) {
	        return new HistoryRecord(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.time = source["time"];
	        this.operation = source["operation"];
	        this.account = source["account"];
	        this.result = source["result"];
	        this.duration = source["duration"];
	        this.traffic = source["traffic"];
	    }
	}
	export class Settings {
	    intervalSeconds: number;
	    autoReconnect: boolean;
	    autoStart: boolean;
	    startMinimized: boolean;
	    accountIndex: number;
	    probeMode: string;
	    probeHost: string;
	    probeHttpUrl: string;
	    probeAttempts: number;
	    probeDelayMs: number;
	    disconnectOnNoInternet: boolean;
	    updateCheckEnabled: boolean;
	    uiTheme: string;
	    proxyEnabled: boolean;
	    proxyType: string;
	    proxyHost: string;
	    proxyPort: string;
	    proxyBypass: string;
	
	    static createFrom(source: any = {}) {
	        return new Settings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.intervalSeconds = source["intervalSeconds"];
	        this.autoReconnect = source["autoReconnect"];
	        this.autoStart = source["autoStart"];
	        this.startMinimized = source["startMinimized"];
	        this.accountIndex = source["accountIndex"];
	        this.probeMode = source["probeMode"];
	        this.probeHost = source["probeHost"];
	        this.probeHttpUrl = source["probeHttpUrl"];
	        this.probeAttempts = source["probeAttempts"];
	        this.probeDelayMs = source["probeDelayMs"];
	        this.disconnectOnNoInternet = source["disconnectOnNoInternet"];
	        this.updateCheckEnabled = source["updateCheckEnabled"];
	        this.uiTheme = source["uiTheme"];
	        this.proxyEnabled = source["proxyEnabled"];
	        this.proxyType = source["proxyType"];
	        this.proxyHost = source["proxyHost"];
	        this.proxyPort = source["proxyPort"];
	        this.proxyBypass = source["proxyBypass"];
	    }
	}

}

export namespace service {
	
	export class ErrorCount {
	    Result: string;
	    Count: number;
	
	    static createFrom(source: any = {}) {
	        return new ErrorCount(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.Result = source["Result"];
	        this.Count = source["Count"];
	    }
	}
	export class LogLine {
	    time: string;
	    level: string;
	    message: string;
	
	    static createFrom(source: any = {}) {
	        return new LogLine(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.time = source["time"];
	        this.level = source["level"];
	        this.message = source["message"];
	    }
	}
	export class StatsSummary {
	    TotalOps: number;
	    DialAttempts: number;
	    DialSuccess: number;
	    DialFail: number;
	    Disconnects: number;
	    TopErrors: ErrorCount[];
	    ReportText: string;
	
	    static createFrom(source: any = {}) {
	        return new StatsSummary(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.TotalOps = source["TotalOps"];
	        this.DialAttempts = source["DialAttempts"];
	        this.DialSuccess = source["DialSuccess"];
	        this.DialFail = source["DialFail"];
	        this.Disconnects = source["Disconnects"];
	        this.TopErrors = this.convertValues(source["TopErrors"], ErrorCount);
	        this.ReportText = source["ReportText"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

