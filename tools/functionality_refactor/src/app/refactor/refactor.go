package refactor

import (
	"fmt"
	"functionality_refactor/app/files"
	"functionality_refactor/app/metrics"
	"functionality_refactor/app/mono2micro"
	"functionality_refactor/app/refactor/values"
	"strconv"

	"functionality_refactor/app/training"
	"sort"
	"sync"
	"time"

	"github.com/go-kit/kit/log"
)

const (
	OnlyLastInvocation                = 0
	AllPreviousInvocations            = -1
	DefaultRefactorRoutineTimeoutSecs = 1440
)

type RefactorHandler interface {
	RefactorDecomposition(*mono2micro.Decomposition, *values.RefactorCodebaseRequest) *values.RefactorCodebaseResponse
}

type DefaultHandler struct {
	logger          log.Logger
	metricsHandler  metrics.MetricsHandler
	trainingHandler training.TrainingHandler
	filesHandler    files.FilesHandler
}

func New(
	logger log.Logger, metricsHandler metrics.MetricsHandler, trainingHandler training.TrainingHandler, filesHandler files.FilesHandler,
) RefactorHandler {
	return &DefaultHandler{
		logger:          log.With(logger, "module", "RefactorHandler"),
		metricsHandler:  metricsHandler,
		trainingHandler: trainingHandler,
		filesHandler:    filesHandler,
	}
}

func (svc *DefaultHandler) RefactorDecomposition(
	decomposition *mono2micro.Decomposition, request *values.RefactorCodebaseRequest,
) *values.RefactorCodebaseResponse {
	// Add to each cluster, the list of controllers that use it
	validControllers := svc.extractValidControllers(decomposition, request)

	// store the initial decomposition data in the file system
	decompositionData := svc.createInitialDecompositionData(request, validControllers)
	err := svc.filesHandler.StoreDecompositionRefactorization(decompositionData)
	if err != nil {
		svc.logger.Log("codebase", request.CodebaseName, "validControllers", len(validControllers), "error", err)
		return nil
	}

	svc.logger.Log("codebase", request.CodebaseName, "validControllers", len(validControllers))

	refactorTimeout := DefaultRefactorRoutineTimeoutSecs
	if request.RefactorTimeOutSecs != 0 {
		refactorTimeout = request.RefactorTimeOutSecs
	}

	for _, controller := range validControllers {
		go func(request *values.RefactorCodebaseRequest, decomposition *mono2micro.Decomposition, controller *mono2micro.Controller) {
			initialRedesign := controller.GetFunctionalityRedesign()

			svc.logger.Log("codebase", request.CodebaseName, "controller", controller.Name, "status", "refactoring...")

			timeoutChannel := make(chan bool, 1)
			redesignChannel := make(chan []*mono2micro.FunctionalityRedesign, 1)

			go func() {
				svc.metricsHandler.CalculateDecompositionMetrics(decomposition, controller, initialRedesign)

				redesignChannel <- svc.createSagaRedesigns(request, decomposition, controller, initialRedesign)
			}()

			go func() {
				time.Sleep(time.Duration(refactorTimeout) * time.Second)
				timeoutChannel <- true
			}()

			// if the controller takes too long to be refactored we want to timeout
			select {
			case sagaRedesigns := <-redesignChannel:
				svc.logger.Log("codebase", request.CodebaseName, "controller", controller.Name, "status", "finished refactoring!")

				bestRedesign := sagaRedesigns[0]

				// TODO: multiple goroutines could try to write in the file at the same time !!!!
				svc.filesHandler.UpdateControllerRefactorization(
					request.CodebaseName,
					request.StrategyName,
					request.DecompositionName,
					svc.createFinalControllerData(controller, initialRedesign, bestRedesign),
				)
				return
			case <-timeoutChannel:
				err := fmt.Sprintf("refactor operation timed out after %d seconds!", refactorTimeout)
				svc.logger.Log("codebase", request.CodebaseName, "controller", controller.Name, "error", err)
				svc.filesHandler.UpdateControllerRefactorization(
					request.CodebaseName,
					request.StrategyName,
					request.DecompositionName,
					&values.Controller{
						Name: controller.Name,
						Monolith: &values.Monolith{
							ComplexityMetrics: &values.ComplexityMetrics{
								SystemComplexity:        initialRedesign.SystemComplexity,
								FunctionalityComplexity: initialRedesign.FunctionalityComplexity,
								InvocationsCount:        initialRedesign.InvocationsCount,
								AccessesCount:           initialRedesign.AccessesCount,
							},
						},
						Error:  err,
						Status: values.ControllerRefactorTimedOut.String(),
					},
				)
				return
			}

		}(request, decomposition, controller)
	}

	return decompositionData
}

func (svc *DefaultHandler) extractValidControllers(
	decomposition *mono2micro.Decomposition, request *values.RefactorCodebaseRequest,
) map[string]*mono2micro.Controller {
	validControllers := map[string]*mono2micro.Controller{}
	var wg sync.WaitGroup
	mapMutex := sync.RWMutex{}

	for _, controller := range decomposition.Controllers {
		if !request.ShouldRefactorController(controller) {
			continue
		}

		wg.Add(1)
		go func(controller *mono2micro.Controller, validControllers map[string]*mono2micro.Controller) {
			defer wg.Done()
			for clusterID := range controller.EntitiesPerCluster {
				cluster := decomposition.GetClusterFromID(clusterID)
				mapMutex.Lock()
				cluster.AddController(controller)
				mapMutex.Unlock()
			}
			mapMutex.Lock()
			validControllers[controller.Name] = controller
			mapMutex.Unlock()
		}(controller, validControllers)
	}
	wg.Wait()
	return validControllers
}

func (svc *DefaultHandler) createSagaRedesigns(
	request *values.RefactorCodebaseRequest,
	decomposition *mono2micro.Decomposition,
	controller *mono2micro.Controller,
	initialRedesign *mono2micro.FunctionalityRedesign,
) []*mono2micro.FunctionalityRedesign {
	sagaRedesigns := []*mono2micro.FunctionalityRedesign{}

	for clusterID := range controller.EntitiesPerCluster {
		cluster := decomposition.Clusters[clusterID]

		redesign := svc.refactorController(request, controller, initialRedesign, cluster)

		svc.metricsHandler.CalculateDecompositionMetrics(decomposition, controller, redesign)

		redesign.OrchestratorID = clusterID

		sagaRedesigns = append(sagaRedesigns, redesign)
	}

	// order the redesigns by ascending complexity
	sort.Slice(sagaRedesigns, func(i, j int) bool {
		if request.MinimizeSumOfComplexities {
			return sagaRedesigns[i].FunctionalityComplexity+sagaRedesigns[i].SystemComplexity < sagaRedesigns[j].FunctionalityComplexity+sagaRedesigns[j].SystemComplexity
		}

		if sagaRedesigns[i].FunctionalityComplexity == sagaRedesigns[j].FunctionalityComplexity {
			return sagaRedesigns[i].SystemComplexity < sagaRedesigns[j].SystemComplexity
		}

		return sagaRedesigns[i].FunctionalityComplexity < sagaRedesigns[j].FunctionalityComplexity
	})

	return sagaRedesigns
}

func (svc *DefaultHandler) refactorController(
	request *values.RefactorCodebaseRequest,
	controller *mono2micro.Controller,
	initialRedesign *mono2micro.FunctionalityRedesign,
	orchestrator *mono2micro.Cluster,
) *mono2micro.FunctionalityRedesign {
	redesign := &mono2micro.FunctionalityRedesign{
		Name:                    controller.Name,
		UsedForMetrics:          true,
		Redesign:                []*mono2micro.Invocation{},
		SystemComplexity:        0,
		FunctionalityComplexity: 0,
		InconsistencyComplexity: 0,
		PivotTransaction:        0,
	}

	// Initialize Invocation, set dependencies and orchestrator
	redesign = svc.addOrchestratorPivotInvocations(orchestrator.Id, initialRedesign, redesign)

	// while any merge is done, iterate all the invocations
	var mergedInvocations int
	iterate := true
	for iterate {
		redesign.Redesign, mergedInvocations = svc.mergeAllPossibleInvocations(request, redesign)
		redesign.RecursiveIterations += 1

		if mergedInvocations < 1 {
			iterate = false
		}
	}

	return redesign
}

func (svc *DefaultHandler) addOrchestratorPivotInvocations(
	orchestratorID int, initialRedesign *mono2micro.FunctionalityRedesign, newRedesign *mono2micro.FunctionalityRedesign,
) *mono2micro.FunctionalityRedesign {
	var invocationID int
	var prevInvocation *mono2micro.Invocation
	for _, initialInvocation := range initialRedesign.Redesign {
		if initialInvocation.ClusterID == -1 {
			continue
		}

		// if this one or the previous is not the orchestrator
		if initialInvocation.ClusterID != orchestratorID && (prevInvocation == nil || prevInvocation.ClusterID != orchestratorID) {
			// add empty orchestrator invocation
			newRedesign.Redesign = append(
				newRedesign.Redesign,
				&mono2micro.Invocation{
					Name:              fmt.Sprintf("%d: %d", invocationID, orchestratorID),
					ID:                invocationID,
					ClusterID:         orchestratorID,
					ClusterAccesses:   [][]interface{}{},
					RemoteInvocations: []int{},
					Type:              "COMPENSATABLE",
				},
			)
			invocationID++
		}

		// add actual invocation
		invocationType := initialInvocation.GetTypeFromAccesses()
		invocation := &mono2micro.Invocation{
			Name:              fmt.Sprintf("%d: %d", invocationID, initialInvocation.ClusterID),
			ID:                invocationID,
			ClusterID:         initialInvocation.ClusterID,
			ClusterAccesses:   initialInvocation.ClusterAccesses,
			RemoteInvocations: []int{},
			Type:              invocationType,
		}

		newRedesign.Redesign = append(newRedesign.Redesign, invocation)
		prevInvocation = invocation
		invocationID++
	}

	newRedesign.InitialInvocationsCount = len(newRedesign.Redesign)
	return newRedesign
}

func (svc *DefaultHandler) mergeAllPossibleInvocations(
	request *values.RefactorCodebaseRequest, redesign *mono2micro.FunctionalityRedesign,
) ([]*mono2micro.Invocation, int) {
	var mergeCount int
	var deleted int
	prevClusterInvocations := map[int][]int{}

	invocations := redesign.Redesign

	for originalInvocationIdx := 0; originalInvocationIdx < len(invocations); originalInvocationIdx++ {
		var addToPreviousInvocations bool
		originalInvocation := invocations[originalInvocationIdx]

		prevInvocations, exists := prevClusterInvocations[originalInvocation.ClusterID]

		if !exists {
			addToPreviousInvocations = true
		} else {
			destinyInvocationIdx := prevInvocations[len(prevInvocations)-1]

			if svc.isMergeForbidden(request, invocations, destinyInvocationIdx, originalInvocationIdx) {
				addToPreviousInvocations = true
			} else {
				invocations, prevClusterInvocations, deleted = svc.mergeInvocations(invocations, prevClusterInvocations, destinyInvocationIdx, originalInvocationIdx)
				svc.pruneInvocationAccesses(invocations[destinyInvocationIdx])

				redesign.MergedInvocationsCount += 1
				mergeCount += 1

				// fix prevInvocations map after merge changes
				for cluster, prevInvocations := range prevClusterInvocations {
					for prevIdx, prevID := range prevInvocations {
						if prevID > originalInvocationIdx-deleted {
							prevClusterInvocations[cluster][prevIdx] = prevID - deleted
						}
					}
				}

				originalInvocationIdx -= deleted
			}
		}

		if addToPreviousInvocations {
			prevClusterInvocations[originalInvocation.ClusterID] = append(prevClusterInvocations[originalInvocation.ClusterID], originalInvocationIdx)
		}
	}

	return invocations, mergeCount
}

func (svc *DefaultHandler) isMergeForbidden(
	request *values.RefactorCodebaseRequest, invocations []*mono2micro.Invocation, destinyInvocationIdx int, originalInvocationIdx int,
) bool {
	var isLastInvocation bool
	if originalInvocationIdx == len(invocations)-1 {
		isLastInvocation = true
	}

	originalInvocation := invocations[originalInvocationIdx]

	if len(originalInvocation.ClusterAccesses) == 0 && !isLastInvocation && destinyInvocationIdx != originalInvocationIdx-1 {
		return true
	}

	// if the invocation is just R, it can be merged
	if !originalInvocation.ContainsLock() || destinyInvocationIdx == originalInvocationIdx-1 {
		return false
	}

	var mergeForbidden bool
	for idx := originalInvocationIdx - 1; idx >= 0; idx-- {
		pivotInvocation := invocations[idx]
		if pivotInvocation.ClusterID == originalInvocation.ClusterID {
			break
		}

		if len(pivotInvocation.ClusterAccesses) == 0 {
			continue
		}

		if request.DataDependenceThreshold == OnlyLastInvocation {
			mergeForbidden = pivotInvocation.ContainsRead()
			break
		} else {
			mergeForbidden = pivotInvocation.ContainsRead()
			if mergeForbidden {
				break
			}

			if (request.DataDependenceThreshold != AllPreviousInvocations) && (originalInvocationIdx-idx == request.DataDependenceThreshold) {
				break
			}
		}
	}

	return mergeForbidden
}

func (svc *DefaultHandler) mergeInvocations(
	invocations []*mono2micro.Invocation, prevInvocations map[int][]int, destinyInvocationIdx int, originalInvocationIdx int,
) ([]*mono2micro.Invocation, map[int][]int, int) {
	newInvocations := []*mono2micro.Invocation{}
	var invocationID int
	var deletedCount int

	for idx, invocation := range invocations {
		if idx == destinyInvocationIdx {
			// append the accesses to the previous invocation
			for _, access := range invocations[originalInvocationIdx].ClusterAccesses {
				invocations[destinyInvocationIdx].ClusterAccesses = append(invocations[destinyInvocationIdx].ClusterAccesses, access)
			}
		}

		var removeInvocation bool
		// if its the invocation to merge or the invocation previous to the one to merge and its empty
		if idx == originalInvocationIdx || (idx == originalInvocationIdx-1 && len(invocation.ClusterAccesses) == 0) {
			// check if its empty (its the orchestrator) and can be deleted
			removeInvocation = true
			deletedCount++
		}

		if removeInvocation {
			var newPrevInvocations []int
			for _, prevIdx := range prevInvocations[invocations[idx].ClusterID] {
				if prevIdx != idx {
					newPrevInvocations = append(newPrevInvocations, prevIdx)
				}
			}

			prevInvocations[invocations[idx].ClusterID] = newPrevInvocations
		} else {
			invocation.ID = invocationID
			newInvocations = append(newInvocations, invocation)
			invocationID++
		}
	}

	return newInvocations, prevInvocations, deletedCount
}

func (svc *DefaultHandler) pruneInvocationAccesses(invocation *mono2micro.Invocation) {
	previousEntityAccesses := map[int]string{}
	newAccesses := [][]interface{}{}

	var containsLock bool

	for idx := range invocation.ClusterAccesses {
		entity := invocation.GetAccessEntityID(idx)
		accessType := invocation.GetAccessType(idx)

		if accessType == "W" {
			containsLock = true
		}

		previousAccessType, exists := previousEntityAccesses[entity]
		if !exists {
			previousEntityAccesses[entity] = accessType
			continue
		} else if previousAccessType == "R" && accessType == "W" {
			previousEntityAccesses[entity] = "RW"
			continue
		} else if previousAccessType == "W" && accessType == "R" {
			previousEntityAccesses[entity] = "WR"
			continue
		} else if previousAccessType == "WR" && accessType == "W" {
			previousEntityAccesses[entity] = "RW"
			continue
		}
	}

	for entity, accessType := range previousEntityAccesses {
		if accessType == "WR" {
			accessType = "W"
		}
		newAccesses = append(newAccesses, []interface{}{accessType, entity})
	}

	if containsLock {
		invocation.Type = "COMPENSATABLE"
	} else {
		invocation.Type = "RETRIABLE"
	}

	invocation.ClusterAccesses = newAccesses
}

func (svc *DefaultHandler) createInitialDecompositionData(
	request *values.RefactorCodebaseRequest, controllers map[string]*mono2micro.Controller,
) *values.RefactorCodebaseResponse {

	controllersData := map[string]*values.Controller{}

	for name, _ := range controllers {
		controllersData[name] = &values.Controller{
			Name:     name,
			Monolith: &values.Monolith{},
			Refactor: &values.Refactor{},
			Error:    "",
			Status:   values.RefactoringController.String(),
		}
	}

	return &values.RefactorCodebaseResponse{
		CodebaseName:            request.CodebaseName,
		StrategyName:            request.StrategyName,
		DecompositionName:       request.DecompositionName,
		Controllers:             controllersData,
		DataDependenceThreshold: request.DataDependenceThreshold,
		Status:                  values.RefactoringCodebase.String(),
	}
}

func (svc *DefaultHandler) createFinalControllerData(
	controller *mono2micro.Controller, initialRedesign *mono2micro.FunctionalityRedesign, sagaRedesign *mono2micro.FunctionalityRedesign,
) *values.Controller {
	systemComplexityReduction := initialRedesign.SystemComplexity - sagaRedesign.SystemComplexity
	functionalityComplexityReduction := initialRedesign.FunctionalityComplexity - sagaRedesign.FunctionalityComplexity

	orchestratorID := sagaRedesign.Redesign[0].ClusterID
	orchestratorName := strconv.Itoa(orchestratorID)

	invocations := []values.Invocation{}
	for _, invocation := range sagaRedesign.Redesign {
		accesses := []values.Access{}
		for idx, _ := range invocation.ClusterAccesses {
			accesses = append(accesses, values.Access{
				EntityID: invocation.GetAccessEntityID(idx),
				Type:     invocation.GetAccessType(idx),
			})
		}

		invocations = append(invocations, values.Invocation{
			ClusterID: invocation.ClusterID,
			Accesses:  accesses,
		})
	}

	return &values.Controller{
		Name: controller.Name,
		Monolith: &values.Monolith{
			ComplexityMetrics: &values.ComplexityMetrics{
				SystemComplexity:        initialRedesign.SystemComplexity,
				FunctionalityComplexity: initialRedesign.FunctionalityComplexity,
				InvocationsCount:        initialRedesign.InvocationsCount,
				AccessesCount:           initialRedesign.AccessesCount,
			},
		},
		Refactor: &values.Refactor{
			Orchestrator: &values.Cluster{
				Name:     orchestratorName,
				ID:       orchestratorID,
				Entities: controller.EntitiesPerCluster[orchestratorID],
			},
			ComplexityMetrics: &values.ComplexityMetrics{
				SystemComplexity:        sagaRedesign.SystemComplexity,
				FunctionalityComplexity: sagaRedesign.FunctionalityComplexity,
				InvocationsCount:        sagaRedesign.InvocationsCount,
				AccessesCount:           sagaRedesign.AccessesCount,
			},
			ExecutionMetrics: &values.ExecutionMetrics{
				SystemComplexityReduction:        systemComplexityReduction,
				FunctionalityComplexityReduction: functionalityComplexityReduction,
				InvocationMerges:                 sagaRedesign.MergedInvocationsCount,
			},
			Invocations: invocations,
		},
		Status: values.ControllerRefactorComplete.String(),
	}
}
