<?php

namespace App\Providers;

use Illuminate\Support\Facades\Gate;
use Laravel\Telescope\IncomingEntry;
use Laravel\Telescope\Telescope;
use Laravel\Telescope\TelescopeApplicationServiceProvider;

/**
 * Laravel Telescope for local development: every request the portal and the API handle, every call the BFF makes to a core service (with its timing and the signed
 * headers' presence, not their values), exceptions, logs, cache and the rate limiter. Never on a server: see AppServiceProvider and config/telescope.php.
 * Open it at http://localhost:8090/telescope on the machine that runs the BFF.
 */
class TelescopeServiceProvider extends TelescopeApplicationServiceProvider
{
    public function register(): void
    {
        $this->hideSensitiveDetails();
        Telescope::filter(fn (IncomingEntry $e) => true);
    }

    /** Tokens, passwords, cookies and the gateway signature never reach the Telescope store, even locally. */
    protected function hideSensitiveDetails(): void
    {
        Telescope::hideRequestParameters(['_token', 'password', 'old_password', 'new_password', 'refresh_token', 'secret', 'client_secret']);
        Telescope::hideRequestHeaders(['cookie', 'x-csrf-token', 'x-xsrf-token', 'authorization', 'x-gateway-signature', 'x-gateway-nonce', 'x-gateway-timestamp']);
        Telescope::hideResponseParameters(['access_token', 'refresh_token', 'access', 'refresh']);
    }

    protected function gate(): void
    {
        Gate::define('viewTelescope', fn ($user = null) => $this->app->environment('local'));
    }
}
