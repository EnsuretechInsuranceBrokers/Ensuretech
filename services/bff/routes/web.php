<?php

use App\Http\Controllers\Portal\PortalController as P;
use Illuminate\Support\Facades\Route;

/* The portal. Everything after /login needs a server-side session; pages call the BFF's own API in-process (App\Frontend\Gateway). */
Route::get('/', fn () => redirect('/app'));
Route::get('/login', [P::class, 'loginForm']);
Route::post('/login', [P::class, 'login'])->middleware('throttle:10,1');
Route::post('/logout', [P::class, 'logout']);

Route::middleware('portal.auth')->prefix('app')->group(function () {
    Route::get('/', [P::class, 'dashboard']);
    Route::get('/employers', [P::class, 'employers']);
    Route::post('/employers', [P::class, 'employerCreate']);
    Route::get('/policies', [P::class, 'policies']);
    Route::get('/policies/{id}', [P::class, 'policy'])->whereNumber('id');
    Route::post('/policies/{id}/approve', [P::class, 'policyApprove'])->whereNumber('id');
    Route::post('/policies/{id}/window', [P::class, 'windowSave'])->whereNumber('id');
    Route::post('/policies/{id}/window/check', [P::class, 'windowCheck'])->whereNumber('id');
    Route::post('/policies/{id}/window/employee', [P::class, 'windowEmployee'])->whereNumber('id');
    Route::get('/announcements', [P::class, 'announcements']);
    Route::post('/announcements', [P::class, 'announcementCreate']);
    Route::post('/announcements/{id}/retire', [P::class, 'announcementRetire'])->whereNumber('id');
    Route::get('/endorsements', [P::class, 'endorsements']);
    Route::post('/endorsements/upload', [P::class, 'upload']);
    Route::get('/endorse', [P::class, 'intake']);
    Route::post('/endorse/plan', [P::class, 'intakePlan']);
    Route::get('/endorse/file', [P::class, 'intakeFile']);
    Route::get('/endorse/template', [P::class, 'intakeTemplate']);
    Route::get('/endorse/grid/{id}', [P::class, 'grid']);
    Route::post('/endorse/grid/{id}/validate', [P::class, 'gridValidate'])->middleware('throttle:300,1');
    Route::match(['put', 'delete'], '/endorse/grid/{id}/row/{key}', [P::class, 'gridRow'])->middleware('throttle:600,1');
    Route::post('/endorse/grid/{id}/sync', [P::class, 'gridSync'])->middleware('throttle:120,1');
    Route::post('/endorse/grid/{id}/submit', [P::class, 'gridSubmit']);
    Route::get('/endorsements/{id}', [P::class, 'job']);
    Route::get('/endorsements/{id}/progress', [P::class, 'progress']);
    Route::post('/endorsements/{id}/{action}', [P::class, 'jobAction']);
    Route::get('/cd', [P::class, 'cd']);
    Route::post('/cd/{policyId}/credit', [P::class, 'cdCredit'])->whereNumber('policyId');
    Route::post('/cd/{policyId}/replenish', [P::class, 'cdReplenish'])->whereNumber('policyId');
    Route::get('/member-ids', [P::class, 'memberIds']);
    Route::get('/member-ids/uploads/{uploadId}', [P::class, 'memberIdsUpload']);
    Route::get('/member-ids/{id}', [P::class, 'memberIdsPolicy'])->whereNumber('id');
    Route::get('/member-ids/{id}/template', [P::class, 'memberIdsTemplate'])->whereNumber('id');
    Route::post('/member-ids/{id}/check', [P::class, 'memberIdsCheck'])->whereNumber('id')->middleware('throttle:30,1');
    Route::post('/member-ids/{id}/apply', [P::class, 'memberIdsApply'])->whereNumber('id')->middleware('throttle:30,1');
    Route::match(['get', 'post'], '/assistant', [P::class, 'assistant']);
});
