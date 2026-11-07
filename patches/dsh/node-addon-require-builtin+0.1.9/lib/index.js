"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
const node_path_1 = __importDefault(require("node:path"));
// [移植补丁 P-01] Android 无对应预编译原生绑定，降级为 --expose-internals 路径。
let api;
try {
    const { createEntryApi } = require('node-addon-native-custom-loader');
    api = createEntryApi(node_path_1.default.resolve(__dirname, '..'));
}
catch (error) {
    if (process.env.DSH_NATIVE_BINDING_STRICT === '1')
        throw error;
    api = {
        requireBuiltin(moduleId) { return require(moduleId); },
        isAllowedInternalId() { return true; },
        getBindingInfo() {
            return Object.freeze({
                mode: 'expose-internals-fallback',
                product: 'node',
                backend: 'napi',
                abi: 'napi',
                bindingPath: null,
                bindingSource: 'fallback',
            });
        },
    };
}
function requireBuiltin(moduleId) {
    return api.requireBuiltin(moduleId);
}
function isAllowedInternalId(moduleId) {
    return api.isAllowedInternalId(moduleId);
}
function getBindingInfo() {
    return api.getBindingInfo();
}
exports.default = {
    requireBuiltin,
    isAllowedInternalId,
    getBindingInfo,
};
