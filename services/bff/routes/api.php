<?php

use App\Bff\Contract;
use App\Http\Controllers\GatewayController;
use App\Http\Controllers\HoneypotController;
use App\Http\Controllers\OpsController;
use App\Http\Controllers\OverviewController;
use Illuminate\Support\Facades\Route;

/*
 | Routes are GENERATED from config/contract.php. There is no catch-all proxy: a path that is not in the contract does not exist here.
 */
$contract = app(Contract::class);

Route::middleware(['bff.limit:bff-anonymous', 'bff.auth', 'bff.limit:bff-tenant', 'bff.limit:bff-user'])->group(function () use ($contract) {
    foreach ($contract->endpoints() as $name => $e) {
        foreach ($e['gateways'] as $gw) {
            $route = Route::match($e['methods'], "v1/$gw/{$e['path']}", GatewayController::class)
                ->defaults('_endpoint', $name)->defaults('_gateway', $gw)->name("bff.$gw.$name")
                ->middleware("bff.gateway:$gw");
            foreach ($e['where'] ?? [] as $param => $re) {
                $route->where($param, $re);
            }
            if (! empty($e['heavy'])) {
                $route->middleware('bff.limit:bff-heavy');
            }
        }
    }
    foreach ($contract->compositions as $name => $c) {
        foreach ($c['gateways'] as $gw) {
            $route = Route::get("v1/$gw/{$c['path']}", [OverviewController::class, 'policy'])
                ->defaults('_gateway', $gw)->name("bff.$gw.$name")->middleware("bff.gateway:$gw");
            foreach ($c['where'] ?? [] as $param => $re) {
                $route->where($param, $re);
            }
        }
    }

    Route::middleware('bff.ops')->prefix('v1/ops')->group(function () {
        Route::get('mitigations', [OpsController::class, 'mitigations']);
        Route::get('upstreams', [OpsController::class, 'upstreams']);
    });
});

// Bait. Unauthenticated on purpose: probes rarely hold a token.
Route::middleware('bff.limit:bff-anonymous')->group(function () {
    foreach (['v1/admin/users/export', 'v1/admin/export', 'v1/debug/config', 'v1/internal/{any}', 'v1/backup/download', 'v1/admin/sql'] as $bait) {
        Route::any($bait, HoneypotController::class)->where('any', '.*');
    }
});
