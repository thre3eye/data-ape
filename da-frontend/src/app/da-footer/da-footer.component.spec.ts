import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DaFooterComponent } from './da-footer.component';

describe('DaFooterComponent', () => {
  let component: DaFooterComponent;
  let fixture: ComponentFixture<DaFooterComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ DaFooterComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(DaFooterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
