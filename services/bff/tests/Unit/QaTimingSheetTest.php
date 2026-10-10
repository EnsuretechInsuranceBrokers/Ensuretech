<?php

namespace Tests\Unit;

use App\Http\Controllers\Portal\QaTimingController;
use PHPUnit\Framework\TestCase;

class QaTimingSheetTest extends TestCase
{
    private function parse(string $csv): array
    {
        $rows = array_map('str_getcsv', array_filter(explode("\n", $csv)));
        array_shift($rows);

        return $rows;
    }

    public function test_the_sheet_has_exactly_the_requested_rows_and_valid_ones_are_clean(): void
    {
        $rows = $this->parse(QaTimingController::csv('valid', 1000, 0));
        $this->assertCount(1000, $rows);
        foreach ($rows as $r) {
            $this->assertMatchesRegularExpression('#^\d\d/\d\d/\d{4}$#', $r[3]);
            $this->assertNotSame('', $r[2]);
            $this->assertContains($r[1], ['Self', 'Spouse', 'SON']);
        }
    }

    public function test_exactly_the_requested_number_of_rows_are_spoiled(): void
    {
        $bad = fn (array $r) => $r[3] === '99/99/9999' || $r[2] === '' || $r[1] === 'NEPHEW';
        $this->assertCount(4, array_filter($this->parse(QaTimingController::csv('mixed', 1000, 4)), $bad));
        $this->assertCount(1000, array_filter($this->parse(QaTimingController::csv('invalid', 1000, 1000)), $bad));
        $this->assertCount(0, array_filter($this->parse(QaTimingController::csv('valid', 1000, 0)), $bad));
    }

    public function test_employee_codes_are_new_every_time(): void
    {
        $a = array_column($this->parse(QaTimingController::csv('valid', 30, 0)), 0);
        $b = array_column($this->parse(QaTimingController::csv('valid', 30, 0)), 0);
        $this->assertSame([], array_intersect($a, $b));
    }
}
