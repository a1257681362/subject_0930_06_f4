# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## F1 风电叶片巡检台账

在骨架上新增叶片运维台账（schema 版本 `f1-1`），已跑通：建档 → 巡检 → 缺陷登记 → 维修 → 验收。电量结算后续再做。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_turbine` | 风机档案：场址、机型、并网时间、退役时间 |
| `t_blade` | 叶片档案：序列号、方位（A/B/C）、材料、装上/拆下时刻 |
| `t_blade_replacement` | 换件记录：旧件 → 新件前后关系 |
| `t_inspection_order` | 巡检工单：无人机影像、巡检时刻、状态机 |
| `t_defect` | 缺陷登记：缺陷等级、维修结果、验收结论 |

### 业务规则

- **编号互斥**：风机编号与叶片编号各自唯一，且两类编号之间也不得重复（`AssetNoGuard` 跨表校验 + 数据库唯一约束）；叶片序列号唯一。
- **归属口径（同一口径）**：风机服役窗口 `[grid_connected_at, decommissioned_at)` 与叶片归属窗口 `[installed_at, removed_at)` 统一为**左闭右开**。叶片未指定装上时刻时默认取所属风机并网时间。任意时刻同一风机同一方位至多一片在役叶片。
- **换件**：旧叶片下架（`removed_at` = 换件时刻）与新叶片上架（`installed_at` = 换件时刻）同事务完成，`t_blade_replacement` 保留旧件与新件的前后关系。
- **工单状态机**：`PENDING → INSPECTED → DEFECT_REGISTERED → REPAIRED → ACCEPTED`；无缺陷时 `INSPECTED → CLOSED`。缺陷状态机：`OPEN → REPAIRED → ACCEPTED`，验收驳回退回 `OPEN` 重新维修；工单下全部缺陷验收通过后工单才闭环。

### 接口一览

```
POST   /api/turbines                          建档（可同时录入首批叶片）
PUT    /api/turbines/{id}                     档案调整（如把并网时间调到临界时间）
GET    /api/turbines/{id}/blades              当前在役叶片
GET    /api/turbines/{id}/blades/at?time=     某时刻归属叶片（口径验证）
GET    /api/turbines/{id}/blades/history      叶片全量档案 + 换件前后关系
POST   /api/turbines/{id}/blades              补录叶片
POST   /api/turbines/{id}/replacements        换件
POST   /api/inspection-orders                 创建巡检工单
POST   /api/inspection-orders/{id}/inspect    提交无人机影像与巡检时刻
POST   /api/inspection-orders/{id}/defects    缺陷登记
POST   /api/inspection-orders/{id}/close      无缺陷闭环
POST   /api/defects/{id}/repair               维修结果回填
POST   /api/defects/{id}/accept               验收（pass=true 闭环 / false 驳回返工）
```

联调期上述业务接口在 Shiro 中放行（`anon`），登录体系接入后收紧。

### 验证

- `mvn test`：`BladeLedgerFlowTest`（全流程、编号互斥、换件前后关系、状态机）+ `BoundaryAttributionTest`（把风机档案调到临界时间后，叶片档案按同一左闭右开口径归属）。
- 测试使用 H2 内存库（`src/test/resources/application.yml`），不污染 `data/` 本地文件库。

## F2 无人机叶片巡检航线

在 F1 基础上新增无人机叶片巡检航线（schema 版本 `f2-1`）：航线按 风机+叶片方位+拍摄距离 配置，内容版本化发布；发布前校验临时禁飞区、风速条件、电量余量；临时改线走两级审批并留版本差异。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_flight_route` | 航线：风机+叶片方位+拍摄距离唯一组合，指向当前发布版本 |
| `t_flight_route_version` | 航线版本：航点串、预计航程、耗电与风速限值、校验留痕；状态 `DRAFT/PUBLISHED/RETIRED` |
| `t_no_fly_zone` | 禁飞区：临时/永久，多边形+限飞高度+生效窗口 `[effective_from, effective_to)`；只取消不删除 |
| `t_flight_task` | 飞行任务：创建时绑定当前发布版本；状态 `PLANNED/TAKEN_OFF/COMPLETED/CANCELLED` |
| `t_route_change_request` | 临时改线申请：基准版本、拟改航点、版本差异 JSON；状态 `PENDING/APPROVED/REJECTED` |
| `t_route_change_approval` | 改线审批记录：每级一条（change_id + step_no 唯一） |
| `t_audit_event` | 审计事件：航线规划与禁飞区关键动作留痕 |
| `t_inspection_order` | 新增 `flight_task_id`、`route_version_id`：历史影像始终引用拍摄时的原航线版本 |

### 业务规则

- **航线配置**：一条航线 = 风机 + 叶片方位（A/B/C）+ 拍摄距离 的唯一组合；航线内容版本化，版本号航线内递增。
- **发布前校验**（`RouteCheckService`）：① 逐航点核对当前生效的临时/永久禁飞区（射线法 + 限飞高度），冲突给出**航点坐标、命中区域和原因**；② 当前风速不得超过航线限值；③ 当前电量 − 预计耗电 不得低于安全余量 20%。校验不过发布被拦，冲突明细随响应返回。
- **版本作用域**：新版本发布只改绑未起飞（`PLANNED`）任务；已起飞任务版本锁定。巡检工单关联飞行任务时带出任务绑定的航线版本，历史影像不随新版本发布改变引用；旧版本退役但保留，航点级内容可查。
- **临时改线**：基于基准版本发起申请，自动留存版本差异（added/removed/moved/paramChanges）。**两级审批**（1 场站 → 2 运检中心）：只完成一半时保持 `PENDING` 停留待处理，不生成新版本；任一级驳回即结案；终审先对拟改内容做发布前校验，通过才落审批记录、生成新版本并发布——校验被拦时申请仍停留待处理，条件解除后可重新终审。
- **可追溯**：航线创建/版本发布/任务改绑/改线审批/禁飞区划定与取消均写入 `t_audit_event`；禁飞区只取消不删除，全量（含已取消、已过期）可查。

### 接口一览

```
POST   /api/routes                            新建航线（含首版本草稿）
GET    /api/routes/{id}                       航线详情（含全部版本）
GET    /api/routes/by-turbine/{turbineId}     风机航线列表
POST   /api/routes/{id}/versions              新增版本草稿
GET    /api/routes/versions/{vid}             版本详情（含航点串，历史版本可查）
POST   /api/routes/versions/{vid}/check       发布前校验（试算，返回冲突明细）
POST   /api/routes/versions/{vid}/publish     发布（校验通过才生效，未起飞任务改绑）
POST   /api/no-fly-zones                      划设禁飞区（临时/永久）
GET    /api/no-fly-zones                      全量列表（?activeOnly=true 只看当前生效）
POST   /api/no-fly-zones/{id}/cancel          取消（不删除，保留追溯）
POST   /api/flight-tasks                      创建飞行任务（绑定当前发布版本）
POST   /api/flight-tasks/{id}/takeoff         起飞（版本自此锁定）
POST   /api/flight-tasks/{id}/complete        完成
POST   /api/flight-tasks/{id}/cancel          取消（仅未起飞）
POST   /api/route-changes                     临时改线申请（留版本差异）
POST   /api/route-changes/{id}/approve        审批当前级次（两级；半批停留待处理）
GET    /api/route-changes/{id}                详情（拟改航点+版本差异+审批记录）
GET    /api/audit-events                      追溯查询（entityType + entityId）
```

巡检工单创建（`POST /api/inspection-orders`）新增可选字段 `flightTaskId`（任务须已起飞，自动带出航线版本）与 `routeVersionId`。

### 验证

- `FlightRouteFlowTest`：航线配置与发布、禁飞区冲突坐标与原因、风速/电量校验、禁飞区生效窗口、新版本只作用未起飞任务、历史影像引用原航线。
- `RouteChangeApprovalTest`：两级审批半批停留待处理、版本差异留存、任一级驳回结案、终审遇禁飞区被拦后停留待处理并可重试、航线与禁飞区审计追溯、历史版本退役后航点可查。

## F3 叶片缺陷管理

在 F1 缺陷登记基础上新增缺陷分类处置（schema 版本 `f3-1`）：裂纹、雷击、结冰、涂层剥落按等级配置处置时限；巡检员在影像上标注位置、尺寸与截图；专家复核可升级或降级但不覆盖初判；已关闭缺陷只能追加说明；影像标注可反查缺陷分类、原单、处理人与异常原因。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_defect` | 新增 `defect_type`、`initial_level`（初判，复核不覆盖）、`repair_advice`、`responsible_person`、`dispose_deadline` |
| `t_defect_dispose_rule` | 处置时限配置：缺陷类型 × 等级唯一，处置时限（小时）+ 维修建议模板；预置 4×4 共 16 条 |
| `t_defect_annotation` | 影像标注：位置、尺寸、截图；冗余 `order_id` 便于反查原单 |
| `t_defect_review` | 专家复核记录：from_level → to_level、复核意见、责任人 |
| `t_defect_note` | 追加说明：只增不改，已关闭缺陷唯一允许的写操作 |

### 业务规则

- **登记**：缺陷必须带分类（`CRACK/LIGHTNING/ICING/COATING_PEELING`）与初判等级；按 类型×等级 配置生成处置截止时间（登记时刻 + 时限）与初始维修建议；未配置的组合拒绝登记。
- **影像标注**：巡检员标注位置、尺寸与截图；未指定影像地址时取工单登记的无人机影像；已关闭缺陷不允许标注。
- **专家复核**：仅待维修（`OPEN`）缺陷可复核；复核等级必须与当前等级不同（构成升级或降级）；**初判等级 `initial_level` 永不覆盖**；等级变化后重新生成维修建议、按新时限从登记时刻重算处置截止时间，并记录责任人；每次复核留痕并写审计事件。
- **已关闭（`ACCEPTED`）**：复核、标注、维修全部拒绝，唯一允许的写操作是追加说明。
- **反查**：从影像标注定位缺陷分类（类型 + 初判/当前等级）、原单（工单号、风机）、处理人（标注人/最近复核人/责任人）与异常原因（缺陷描述 + 最近复核意见）。

### 接口一览

```
GET    /api/defect-rules                    处置时限配置列表
PUT    /api/defect-rules/{id}               调整处置时限与建议模板
POST   /api/defects/{id}/annotations        影像标注（位置、尺寸、截图）
POST   /api/defects/{id}/review             专家复核（升/降级，不覆盖初判）
POST   /api/defects/{id}/notes              追加说明（已关闭唯一允许的写操作）
GET    /api/defects/{id}                    缺陷详情（标注+复核记录+说明）
GET    /api/annotations/{id}/trace          反查：分类/原单/处理人/异常原因
```

缺陷登记（`POST /api/inspection-orders/{id}/defects`）新增必填字段 `defectType`；`defectLevel` 作为初判等级登记。

### 验证

- `DefectManagementTest`：时限配置预置与调整、登记生成时限与建议、影像标注落库、复核升降级（初判不变/建议重生成/责任人记录/时限重算/同级拒绝）、已关闭只能追加说明、标注反查三要素、未配置组合拒绝登记。

## F4 高空作业排班

在 F1–F3 基础上新增高空作业排班（schema 版本 `f4-1`）：读取风速、降雨、雷电三类安全窗口，结合人员资质、装备可用性与维修时长生成候选班次；窗口收窄只预警不静默改期；作业开始前强制二次确认天气；延期保留原计划与通知回执；过期资质人员不得排入。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_weather_window` | 风速/降雨/雷电安全窗口，统一左闭右开 `[window_from, window_to)`；风速窗口带 `safe_max_value`；按 `(source_channel, source_doc_no, weather_type)` 幂等；`replaces_source_doc_no` 标记修正预报替换的旧窗口（旧窗口留痕但不参与排班） |
| `t_worker` / `t_worker_qualification` | 作业人员与资质；资质有效期 `[valid_from, valid_to)` 左闭右开，到期当刻即失效 |
| `t_equipment` | 装备与可用窗口；维修中（`IN_REPAIR`，`[now, repair_finish_at)`）不可被新班次占用 |
| `t_work_shift` | 候选/确认班次；`planned_*` 为原计划永不修改，`current_*` 为当前执行时间；`feasible_*` 为生成时刻三窗口交集；`result_key` 保证同一排产意图只落一条结果 |
| `t_shift_warning` | 收窄预警（`NARROWED`/`UNCOVERED`）；按 `warning_key` 幂等，重复扫描不重复告警 |
| `t_postponement` | 延期记录：`seq` 班次内递增，`original_*` 始终引用最初原计划，`new_*` 为本次延期时间 |
| `t_notification_receipt` | 通知送达回执；按 `(shift_id, postponement_id, channel)` 幂等，重试复用不重复通知 |

### 业务规则

- **候选班次生成**：三类气象窗口同类先取并集（多来源补传互补）、三类再取交集，一律左闭右开，交集取 `[max(from), min(to))`；在交集中按维修时长寻找不早于 `notBefore` 的最早连续可行起点；再校验装备可用性与班组资质。任一条件不满足整单不落库，返回全部拦截原因（窗口缺失/维修时长放不下/装备维修中/有效资质人员不足）。
- **资质口径**：作业开始时刻 `t` 必须满足 `valid_from <= t < valid_to`，资质等级不低于要求（`ADVANCED` 兼容 `PRIMARY`）。**过期（含到期当刻）人员不会被排入**；人员不足按“需要 N 人仅 M 人”拦截。延期时对新开始时刻重新逐人复核资质。
- **窗口收窄只预警不改期**：气象数据补传/补录/重试或修正预报（`replaces_source_doc_no`）后，重算交集。仍覆盖当前作业区间但区间变短 → `NARROWED`；不再完整覆盖 → `UNCOVERED`。系统只写预警并发通知，**绝不自动修改计划时间、不自动生成延期**；改期只能由人工显式调用延期接口。
- **开工前二次确认天气**：`CANDIDATE/CONFIRMED` 班次开工前必须重新读取三窗口，确认当前作业区间被完整覆盖、现场结论安全（`fieldSafe`）、实测风速不超过覆盖窗口的安全上限；通过才置 `CONFIRMED` 并留天气快照，未通过禁止开工（不自动改期）。
- **延期保留原计划与回执**：延期写 `t_postponement`（`original_*` 永不漂移），只更新班次 `current_*`；延期目标时段必须被最新窗口交集完整覆盖。按 `SMS/APP/EMAIL` 等通道各留一条送达回执，发送重试复用同一回执。
- **幂等（补传/补录/重试只有一条可用结果）**：气象窗口按来源单据去重；候选班次按 `result_key`（风机+工作内容+资质+人数+时长+装备类型+不早于时刻）去重，重复提交返回同一条；收窄预警按收窄后区间去重；通知回执按通道去重。

### 接口一览

```
POST   /api/weather-windows                    接入风速/降雨/雷电窗口（补传/补录/重试幂等；replacesSourceDocNo 修正预报）
GET    /api/weather-windows                    查询某风机某类窗口（含场址级，已剔除被替换窗口）
POST   /api/workers                            作业人员建档
POST   /api/workers/{id}/qualifications        登记资质（有效期左闭右开）
GET    /api/workers/{id}/qualifications        资质列表
POST   /api/equipments                         装备建档
POST   /api/equipments/{id}/repair             装备送修（维修完成前不可用）
POST   /api/equipments/{id}/repair-finish      维修完成恢复可用
POST   /api/high-work/shifts                   生成候选班次（同一排产意图只返回一条）
GET    /api/high-work/shifts/{id}              班次详情（含预警/延期/回执）
POST   /api/high-work/shifts/{id}/confirm-weather  开工前天气二次确认
POST   /api/high-work/shifts/{id}/start        开工（须先通过二次确认）
POST   /api/high-work/shifts/{id}/complete     完成
POST   /api/high-work/shifts/{id}/cancel       取消（未开工）
POST   /api/high-work/shifts/{id}/postpone     人工延期（保留原计划+通知回执）
POST   /api/high-work/shifts/{id}/rescan       单班次收窄即时核对
POST   /api/high-work/shifts/rescan-all        全量收窄扫描
GET    /api/high-work/shifts/{id}/warnings     收窄预警
GET    /api/high-work/shifts/{id}/postponements 延期记录（含原计划）
GET    /api/high-work/shifts/{id}/receipts     通知回执
```

收窄扫描另有定时任务 `ShiftWarningJob`（默认 5 分钟，开关 `evops.shift-warning.scan.enabled`，测试环境关闭）；定时任务只预警，任何异常都不会触发改期。

### 验证

- `HighWorkScheduleTest`（10 例）：三窗口交集+资质+装备+维修时长生成候选；交集放不下被拦截；到期当刻资质失效不可排入；维修中装备不可占用；补传/重试窗口与排班幂等只一条；修正预报收窄预警且原计划/状态不变、重复扫描不重复告警；收窄致不覆盖时高危预警不自动改期；开工前二次确认（覆盖/实测风速超限/现场不安全）；延期保留原计划、回执按通道幂等可重试、二次延期 `seq` 递增且原计划不漂移；延期到资质过期时刻被拒绝。
