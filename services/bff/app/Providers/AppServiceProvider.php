<?php

namespace App\Providers;

use App\Bff\Contract;
use App\Bff\JwtVerifier;
use App\Bff\Mitigations;
use App\Bff\Problem;
use App\Bff\Upstream;
use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Contract::class, fn () => new Contract);
        $this->app->singleton(JwtVerifier::class);
        $this->app->singleton(Upstream::class);
        $this->app->singleton(Mitigations::class, fn () => new Mitigations);
    }

    public function boot(): void
    {
        $cfg = fn (string $k) => (int) config("bff.rate_limits.$k");
        $principal = fn (Request $r) => $r->attributes->get('principal');

        RateLimiter::for('bff-anonymous', fn (Request $r) => Limit::perMinute($cfg('anonymous_per_minute'))->by('ip:'.$r->ip()));
        RateLimiter::for('bff-auth', fn (Request $r) => Limit::perMinute($cfg('auth_per_minute'))->by('auth:'.$r->ip()));
        RateLimiter::for('bff-tenant', fn (Request $r) => Limit::perMinute($cfg('tenant_per_minute'))->by('tenant:'.($principal($r)?->tenantKey() ?? $r->ip())));
        RateLimiter::for('bff-user', fn (Request $r) => Limit::perMinute($cfg('user_per_minute'))->by('user:'.($principal($r)?->userId ?? $r->ip())));
        RateLimiter::for('bff-heavy', fn (Request $r) => Limit::perMinute($cfg('heavy_per_minute'))->by('heavy:'.($principal($r)?->userId ?? $r->ip())));
    }
}
