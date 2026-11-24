import { CapacitorHttp } from '@capacitor/core'
import { AbsDatabase } from './capacitor/AbsDatabase'
import { AbsLogger } from './capacitor/AbsLogger'

export default function ({ store, $db, $socket }, inject) {
  const nativeHttp = {
    async request(method, _url, data, options = {}) {
      // When authorizing before a config is set, server config gets passed in as an option
      let serverConnectionConfig = options.serverConnectionConfig || store.state.user.serverConnectionConfig
      delete options.serverConnectionConfig

      let url = _url
      let headers = {}
      if (!url.startsWith('http') && !url.startsWith('capacitor')) {
        const bearerToken = store.getters['user/getToken']
        if (bearerToken) {
          headers['Authorization'] = `Bearer ${bearerToken}`
        } else {
          console.warn('[nativeHttp] No Bearer Token for request')
        }
        if (serverConnectionConfig?.address) {
          url = `${serverConnectionConfig.address}${url}`
        }
      }
      if (data) {
        headers['Content-Type'] = 'application/json'
      }
      if (options.headers) {
        headers = { ...headers, ...options.headers }
        delete options.headers
      }
      console.log(`[nativeHttp] Making ${method} request to ${url}`)

      return CapacitorHttp.request({
        method,
        url,
        data,
        headers,
        ...options
      }).then((res) => {
        if (res.status === 401) {
          console.error(`[nativeHttp] 401 status for url "${url}"`)
          // Handle refresh token automatically
          return this.handleTokenRefresh(method, url, data, headers, options, serverConnectionConfig)
        }
        if (res.status >= 400) {
          console.error(`[nativeHttp] ${res.status} status for url "${url}"`)
          const message = typeof res.data === 'string' ? res.data : `HTTP ${res.status}`
          throw new Error(message)
        }
        return res.data
      })
    },

    /**
     * Handles token refresh when a 401 Unauthorized response is received
     * @param {string} method - HTTP method
     * @param {string} url - Full URL
     * @param {*} data - Request data
     * @param {Object} headers - Request headers
     * @param {Object} options - Additional options
     * @param {{ id: string, address: string, version: string }} serverConnectionConfig
     * @returns {Promise} - Promise that resolves with the response data
     */
    async handleTokenRefresh(method, url, data, headers, options, serverConnectionConfig) {
      AbsLogger.info({ tag: 'auth', message: `refresh started for ${url}` })
      if (!serverConnectionConfig?.id) {
        AbsLogger.error({ tag: 'auth', message: 'refresh skipped; no server config. Playback stays up' })
        throw new Error('No server connection available')
      }

      const refreshed = await AbsDatabase.refreshAccessToken({ serverConnectionConfigId: serverConnectionConfig.id })
      if (!refreshed?.accessToken) {
        AbsLogger.error({ tag: 'auth', message: `refresh failed (${refreshed?.error || 'unknown'}). Playback stays up` })
        throw new Error(refreshed?.error || 'Token refresh failed')
      }

      store.commit('user/setAccessToken', refreshed.accessToken)
      AbsLogger.info({ tag: 'auth', message: 'refresh succeeded; retrying request' })
      const retryResponse = await CapacitorHttp.request({
        method,
        url,
        data,
        headers: {
          ...headers,
          Authorization: `Bearer ${refreshed.accessToken}`
        },
        connectTimeout: 10000,
        readTimeout: 10000,
        ...options
      })

      if (retryResponse.status >= 400) {
        AbsLogger.error({ tag: 'auth', message: `retry HTTP ${retryResponse.status}. Playback stays up` })
        const message = typeof retryResponse.data === 'string' ? retryResponse.data : `HTTP ${retryResponse.status}`
        throw new Error(message)
      }

      return retryResponse.data
    },

    get(url, options = {}) {
      return this.request('GET', url, undefined, options)
    },
    post(url, data, options = {}) {
      return this.request('POST', url, data, options)
    },
    patch(url, data, options = {}) {
      return this.request('PATCH', url, data, options)
    },
    delete(url, options = {}) {
      return this.request('DELETE', url, undefined, options)
    }
  }
  inject('nativeHttp', nativeHttp)
}
