import React, {useEffect, useState} from 'react';
import { RepositoryService } from '../../services/RepositoryService';
import Row from 'react-bootstrap/Row';
import Breadcrumb from 'react-bootstrap/Breadcrumb';
import {useParams} from "react-router-dom";
import {AccessesSciPyDecompositionForm} from "./forms/AccessesSciPyDecompositionForm";
import {toast, ToastContainer} from "react-toastify";
import {StrategyType} from "../../models/strategy/Strategy";

export const Decompositions = () => {
    let { codebaseName, strategyName, dendrogramName } = useParams();
    const [dendrogram, setDendrogram] = useState({});
    const [decompositions, setDecompositions] = useState([]);

    //Executed on mount
    useEffect(() => {
        const service = new RepositoryService();
        service.getDendrogram(dendrogramName).then(response => {
            setDendrogram(response);
            loadDecompositions();
        });
    }, []);

    function loadDecompositions() {
        const service = new RepositoryService();
        const toastId = toast.loading("Fetching Decompositions...");
        service.getDecompositions(
            dendrogramName
        ).then(response => {
            setDecompositions(response);
            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: "Decompositions Loaded.", isLoading: false});
            setTimeout(() => {toast.dismiss(toastId)}, 1000);
        }).catch(() => {
            toast.update(toastId, {type: toast.TYPE.ERROR, render: "Error Loading Decompositions.", isLoading: false});
        });
    }

    function handleDeleteDecomposition(decompositionName) {
        const toastId = toast.loading("Deleting " + decompositionName + "...");
        const service = new RepositoryService();
        service.deleteDecomposition(decompositionName).then(() => {
            loadDecompositions();
            toast.update(toastId, {type: toast.TYPE.SUCCESS, render: "Decomposition deleted.", isLoading: false});
            setTimeout(() => {toast.dismiss(toastId)}, 1000);
        }).catch(() => {
            toast.update(toastId, {type: toast.TYPE.ERROR, render: "Error deleting " + decompositionName + ".", isLoading: false});
        });
    }

    function renderBreadCrumbs() {
        return (
            <Breadcrumb>
                <Breadcrumb.Item href="/">
                    Home
                </Breadcrumb.Item>
                <Breadcrumb.Item href="/codebases">
                    Codebases
                </Breadcrumb.Item>
                <Breadcrumb.Item href={`/codebases/${codebaseName}`}>
                    {codebaseName}
                </Breadcrumb.Item>
                <Breadcrumb.Item href={`/codebases/${codebaseName}/${strategyName}`}>
                    {strategyName}
                </Breadcrumb.Item>
                <Breadcrumb.Item active>
                    {dendrogramName}
                </Breadcrumb.Item>
            </Breadcrumb>
        );
    }

    return (
        <div style={{ paddingLeft: "2rem" }}>
            <ToastContainer
                position="top-center"
                theme="colored"
            />

            {renderBreadCrumbs()}

            <h4 style={{color: "#666666"}}>
                Decomposition Creation Method
            </h4>

            {dendrogram.type === StrategyType.ACCESSES_SCIPY &&
                <AccessesSciPyDecompositionForm
                    loadDecompositions={loadDecompositions}
                />
            }

            {decompositions.length !== 0 &&
                <h4 style={{color: "#666666", marginTop: "16px"}}>
                    Decompositions
                </h4>
            }

            <Row className={"d-flex flex-wrap mw-100"} style={{gap: '1rem 1rem'}}>
                {decompositions.map(decomposition => decomposition.printCard(loadDecompositions, handleDeleteDecomposition))}
            </Row>
        </div>
    );
}