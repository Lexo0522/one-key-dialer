export namespace main {
	
	export class BroadbandCredentialDTO {
	    username: string;
	    hasPassword: boolean;
	
	    static createFrom(source: any = {}) {
	        return new BroadbandCredentialDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.username = source["username"];
	        this.hasPassword = source["hasPassword"];
	    }
	}
	export class AppState {
	    version: string;
	    displayVersion: string;
	    settings: model.Settings;
	    broadband: BroadbandCredentialDTO;
	    online: boolean;
	    sysOnline: boolean;
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
	        this.broadband = this.convertValues(source["broadband"], BroadbandCredentialDTO);
	        this.online = source["online"];
	        this.sysOnline = source["sysOnline"];
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
	    current: boolean;
	
	    static createFrom(source: any = {}) {
	        return new DeviceOption(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.port = source["port"];
	        this.device = source["device"];
	        this.existing = source["existing"];
	        this.default = source["default"];
	        this.current = source["current"];
	    }
	}
	export class EthLinkDTO {
	    descr: string;
	    up: boolean;
	    speedMbps: number;
	
	    static createFrom(source: any = {}) {
	        return new EthLinkDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.descr = source["descr"];
	        this.up = source["up"];
	        this.speedMbps = source["speedMbps"];
	    }
	}
	export class LangPayload {
	    lang: string;
	    system: string;
	    auto: boolean;
	
	    static createFrom(source: any = {}) {
	        return new LangPayload(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.lang = source["lang"];
	        this.system = source["system"];
	        this.auto = source["auto"];
	    }
	}
	export class PortalCredentialDTO {
	    username: string;
	    hasPassword: boolean;
	
	    static createFrom(source: any = {}) {
	        return new PortalCredentialDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.username = source["username"];
	        this.hasPassword = source["hasPassword"];
	    }
	}
	export class PortalTestResult {
	    ok: boolean;
	    detail: string;
	
	    static createFrom(source: any = {}) {
	        return new PortalTestResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.detail = source["detail"];
	    }
	}
	export class PppStatsDTO {
	    available: boolean;
	    connected: boolean;
	    localIp: string;
	    serverIp: string;
	    bps: number;
	    bytesUp: number;
	    bytesDown: number;
	    errTotal: number;
	    durationSec: number;
	
	    static createFrom(source: any = {}) {
	        return new PppStatsDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.available = source["available"];
	        this.connected = source["connected"];
	        this.localIp = source["localIp"];
	        this.serverIp = source["serverIp"];
	        this.bps = source["bps"];
	        this.bytesUp = source["bytesUp"];
	        this.bytesDown = source["bytesDown"];
	        this.errTotal = source["errTotal"];
	        this.durationSec = source["durationSec"];
	    }
	}
	export class WifiNetworkDTO {
	    ssid: string;
	    signalQuality: number;
	    secured: boolean;
	    connected: boolean;
	    hasProfile: boolean;
	    auth: string;
	
	    static createFrom(source: any = {}) {
	        return new WifiNetworkDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ssid = source["ssid"];
	        this.signalQuality = source["signalQuality"];
	        this.secured = source["secured"];
	        this.connected = source["connected"];
	        this.hasProfile = source["hasProfile"];
	        this.auth = source["auth"];
	    }
	}
	export class WifiStatusDTO {
	    available: boolean;
	    connected: boolean;
	    ssid: string;
	    signalQuality: number;
	    phase: string;
	    autoConnect: boolean;
	    preferredSsid: string;
	
	    static createFrom(source: any = {}) {
	        return new WifiStatusDTO(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.available = source["available"];
	        this.connected = source["connected"];
	        this.ssid = source["ssid"];
	        this.signalQuality = source["signalQuality"];
	        this.phase = source["phase"];
	        this.autoConnect = source["autoConnect"];
	        this.preferredSsid = source["preferredSsid"];
	    }
	}

}

export namespace model {
	
	export class Settings {
	    intervalSeconds: number;
	    autoReconnect: boolean;
	    autoStart: boolean;
	    startMinimized: boolean;
	    probeMode: string;
	    probeHost: string;
	    probeHttpUrl: string;
	    probeAttempts: number;
	    probeDelayMs: number;
	    disconnectOnNoInternet: boolean;
	    updateCheckEnabled: boolean;
	    uiTheme: string;
	    pppoePort: string;
	    pppoeDevice: string;
	    proxyEnabled: boolean;
	    proxyType: string;
	    proxyHost: string;
	    proxyPort: string;
	    proxyBypass: string;
	    lowMemRender: boolean;
	    wifiAutoConnect: boolean;
	    wifiPreferredSsid: string;
	    portalAuthEnabled: boolean;
	    portalLoginUrl: string;
	    portalMethod: string;
	    portalBody: string;
	    portalHeaders: string;
	    portalSuccessHint: string;
	
	    static createFrom(source: any = {}) {
	        return new Settings(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.intervalSeconds = source["intervalSeconds"];
	        this.autoReconnect = source["autoReconnect"];
	        this.autoStart = source["autoStart"];
	        this.startMinimized = source["startMinimized"];
	        this.probeMode = source["probeMode"];
	        this.probeHost = source["probeHost"];
	        this.probeHttpUrl = source["probeHttpUrl"];
	        this.probeAttempts = source["probeAttempts"];
	        this.probeDelayMs = source["probeDelayMs"];
	        this.disconnectOnNoInternet = source["disconnectOnNoInternet"];
	        this.updateCheckEnabled = source["updateCheckEnabled"];
	        this.uiTheme = source["uiTheme"];
	        this.pppoePort = source["pppoePort"];
	        this.pppoeDevice = source["pppoeDevice"];
	        this.proxyEnabled = source["proxyEnabled"];
	        this.proxyType = source["proxyType"];
	        this.proxyHost = source["proxyHost"];
	        this.proxyPort = source["proxyPort"];
	        this.proxyBypass = source["proxyBypass"];
	        this.lowMemRender = source["lowMemRender"];
	        this.wifiAutoConnect = source["wifiAutoConnect"];
	        this.wifiPreferredSsid = source["wifiPreferredSsid"];
	        this.portalAuthEnabled = source["portalAuthEnabled"];
	        this.portalLoginUrl = source["portalLoginUrl"];
	        this.portalMethod = source["portalMethod"];
	        this.portalBody = source["portalBody"];
	        this.portalHeaders = source["portalHeaders"];
	        this.portalSuccessHint = source["portalSuccessHint"];
	    }
	}

}

export namespace service {
	
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

}

